#!/usr/bin/env python3
"""Rebuild a whisper.cpp ggml model from the sherpa-onnx (k2-fsa) ONNX export of OpenAI Whisper.

The ONNX export keeps every weight of the original checkpoint. Named initializers are used directly; anonymous ones
(MatMul/Conv/Add constants) are identified by the module path in their node name, or, where the export lost the block
index, by the nearest layer norm upstream in the graph. Matrix weights are stored transposed in ONNX ([in, out]) and are
transposed back to PyTorch layout ([out, in]). Output format and tensor types follow whisper.cpp's
models/convert-pt-to-ggml.py (f16 for matrices, f32 for vectors, conv biases and positional embeddings).

Usage: onnx_whisper_to_ggml.py encoder.onnx decoder.onnx path-to-openai-whisper-package out.bin
"""
import base64, re, struct, sys
from collections import deque
from pathlib import Path
import numpy as np
import onnx
from onnx import numpy_helper

enc_path, dec_path, whisper_pkg, out_path = sys.argv[1:5]
enc = onnx.load(enc_path)
dec = onnx.load(dec_path)
meta = {p.key: p.value for p in enc.metadata_props}
hp = {k: int(meta[k]) for k in ["n_mels", "n_audio_ctx", "n_audio_state", "n_audio_head", "n_audio_layer",
                                 "n_vocab", "n_text_ctx", "n_text_state", "n_text_head", "n_text_layer"]}

state = {}


def put(name, array):
    if name in state:
        raise SystemExit(f"duplicate tensor {name}")
    state[name] = array


def tensors(model):
    return {i.name: numpy_helper.to_array(i) for i in model.graph.initializer}


def ln_of(model, node, inits):
    """Nearest upstream layer norm (block index, kind) of a node, found by breadth-first search."""
    producer = {o: n for n in model.graph.node for o in n.output}
    seen, queue = set(), deque([node])
    while queue:
        n = queue.popleft()
        for inp in n.input:
            m = re.match(r"textDecoder\.blocks\.(\d+)\.(attn_ln|cross_attn_ln|mlp_ln)\.", inp)
            if m and n is not node:
                return int(m.group(1)), m.group(2)
        for inp in n.input:
            p = producer.get(inp)
            if p is not None and id(p) not in seen:
                seen.add(id(p))
                queue.append(p)
    return None


def linear_role(node_name):
    m = re.search(r"/(query|key|value|out|mlp\.0|mlp\.2)(_\d+)?/(MatMul|Add)$", node_name)
    return m.group(1) if m else None


# --- Encoder graph: audio encoder plus the decoder's cross-attention key/value projections ---
inits = tensors(enc)
consumer = {}
for n in enc.graph.node:
    for inp in n.input:
        if inp in inits:
            consumer.setdefault(inp, []).append(n)
for name, arr in inits.items():
    if name.startswith("audioEncoder."):
        put("encoder." + name[len("audioEncoder."):], arr)
        continue
    node = consumer[name][0]
    kind = "weight" if node.op_type in ("MatMul", "Conv") and arr.ndim >= 2 else "bias"
    m = re.match(r"/audioEncoder/(blocks\.\d+)/(attn|mlp)/(query|key|value|out|mlp\.0|mlp\.2)/(MatMul|Add)$", node.name)
    if m:
        put(f"encoder.{m.group(1)}.{m.group(2) if m.group(2) == 'attn' else 'mlp'}.{m.group(3)}.{kind}"
            .replace("mlp.mlp.", "mlp."), arr.T if kind == "weight" else arr)
        continue
    m = re.match(r"/audioEncoder/(conv[12])/Conv$", node.name)
    if m:
        put(f"encoder.{m.group(1)}.{kind}", arr)
        continue
    m = re.match(r"/(key|value)(?:_(\d+))?/(MatMul|Add)$", node.name)
    if m:  # cross-attention K/V of decoder block k, computed once per utterance in the encoder graph
        block = int(m.group(2) or 0)
        put(f"decoder.blocks.{block}.cross_attn.{m.group(1)}.{kind}", arr.T if kind == "weight" else arr)
        continue
    raise SystemExit(f"unmapped encoder initializer {name} used by {node.name}")

# --- Decoder graph ---
inits = tensors(dec)
consumer = {}
for n in dec.graph.node:
    for inp in n.input:
        if inp in inits:
            consumer.setdefault(inp, []).append(n)
for name, arr in inits.items():
    if name.startswith("textDecoder."):
        put("decoder." + name[len("textDecoder."):], arr)
        continue
    node = consumer[name][0]
    role = linear_role(node.name)
    if role is None:
        # e.g. a transposed copy of the token embedding for the output projection (tied weights in Whisper)
        print(f"skipping {name} ({node.op_type} {node.name}) {list(arr.shape)}")
        continue
    block, ln = ln_of(dec, node, inits)
    kind = "weight" if node.op_type == "MatMul" else "bias"
    module = {"attn_ln": "attn", "cross_attn_ln": "cross_attn", "mlp_ln": "mlp"}[ln]
    if role.startswith("mlp"):
        assert module == "mlp", (name, node.name, ln)
        key = f"decoder.blocks.{block}.{role}.{kind}"
    else:
        key = f"decoder.blocks.{block}.{module}.{role}.{kind}"
    put(key, arr.T if kind == "weight" else arr)

# Consistency with the original checkpoint layout
expected = 0
for b in range(hp["n_audio_layer"]):
    expected += 2 * 2 + 3 + 1 + 3 + 1 + 4  # 2 LNs (w,b), q(w,b) k(w) v(w,b) out(w,b), mlp 0/2 (w,b)
for b in range(hp["n_text_layer"]):
    expected += 3 * 2 + 2 * (3 + 1 + 3 + 1 - 0) + 4
missing = [k for k in [f"decoder.blocks.{b}.cross_attn.key.weight" for b in range(hp["n_text_layer"])] if k not in state]
assert not missing, missing
for t in ["encoder.conv1.weight", "encoder.conv2.bias", "encoder.ln_post.weight", "decoder.ln.bias",
          "decoder.token_embedding.weight", "decoder.positional_embedding"]:
    assert t in state, t
print(f"{len(state)} tensors")

assets = Path(whisper_pkg) / "assets"
filters = np.load(assets / "mel_filters.npz")[f"mel_{hp['n_mels']}"]
multilingual = hp["n_vocab"] >= 51865
tok_file = assets / ("multilingual.tiktoken" if multilingual else "gpt2.tiktoken")
tokens = {base64.b64decode(t): int(r) for t, r in (line.split() for line in tok_file.read_bytes().splitlines() if line)}

with open(out_path, "wb") as f:
    f.write(struct.pack("i", 0x67676d6c))
    for k in ["n_vocab", "n_audio_ctx", "n_audio_state", "n_audio_head", "n_audio_layer", "n_text_ctx",
              "n_text_state", "n_text_head", "n_text_layer", "n_mels"]:
        f.write(struct.pack("i", hp[k]))
    f.write(struct.pack("i", 1))  # f16
    f.write(struct.pack("ii", *filters.shape))
    f.write(filters.astype(np.float32).tobytes())
    f.write(struct.pack("i", len(tokens)))
    for key in tokens:
        f.write(struct.pack("i", len(key)))
        f.write(key)
    # Same order as the PyTorch state dict: encoder first, then decoder
    for name in sorted(state, key=lambda n: (not n.startswith("encoder"), n)):
        data = np.asarray(state[name]).squeeze()
        if name in ("encoder.conv1.bias", "encoder.conv2.bias"):
            data = data.reshape(data.shape[0], 1)
        f32 = data.ndim < 2 or name in ("encoder.conv1.bias", "encoder.conv2.bias",
                                        "encoder.positional_embedding", "decoder.positional_embedding")
        data = data.astype(np.float32 if f32 else np.float16)
        encoded = name.encode()
        f.write(struct.pack("iii", data.ndim, len(encoded), 0 if f32 else 1))
        for i in range(data.ndim):
            f.write(struct.pack("i", data.shape[data.ndim - 1 - i]))
        f.write(encoded)
        f.write(np.ascontiguousarray(data).tobytes())
print("wrote", out_path)
