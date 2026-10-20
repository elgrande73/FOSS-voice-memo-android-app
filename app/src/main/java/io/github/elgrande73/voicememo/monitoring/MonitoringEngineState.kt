package io.github.elgrande73.voicememo.monitoring

/** Real state of the monitoring capture/detection engine, as shown on the dashboard. */
enum class MonitoringEngineState {
    /** No monitoring service, the microphone is not used. */
    STOPPED,
    /** The service is acquiring the microphone and starting the capture. */
    STARTING,
    /** The microphone is being captured and analysed. */
    ACTIVE,
    /** Another workflow (a Settings phrase recording) holds the microphone; monitoring resumes when it is released. */
    WAITING_FOR_MICROPHONE,
    /** The microphone could not be opened (permission, hardware); the service keeps retrying. */
    UNAVAILABLE,
    /** The service is shutting down and releasing the microphone. */
    STOPPING
}
