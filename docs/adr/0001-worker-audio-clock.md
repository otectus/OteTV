# ADR 0001: Direct media is paced by the client's audible audio clock

Status: accepted (implemented in 0.1.0)

## Context

Direct media decodes in the out-of-process GStreamer worker, while sound plays through Minecraft's own OpenAL output so
it obeys the master and Records volume sliders, positional attenuation and mute. Two clocks therefore exist: the
worker's pipeline clock and the moment a sample actually leaves OpenAL. If video follows the pipeline clock, lip sync
depends on OpenAL's queue depth, which varies by device, driver and load. The brief (sections 11 and 15) requires a
measured audio/video offset and no cumulative drift.

## Decision

- The worker does not render on its own clock when audio exists. It decodes ahead only within a credit window: the
  client reports consumed PCM bytes (`AUDIO_CREDIT`) and the worker keeps at most one second of audio in flight.
- The client reports which media timestamp is audible now (`CLOCK_REPORT`), measured from the OpenAL source's queue
  position (`AL_BUFFERS_QUEUED` and the sample offset of the oldest buffer) through an access transformer on
  `com.mojang.blaze3d.audio.Channel.source`. When nothing can be heard (sound off, no channel), a virtual clock drains
  the ring in real time so video and the shared position keep moving.
- The worker releases each decoded frame when the reported audible timestamp reaches its PTS minus a 25 ms lead
  (upload and display latency), dropping frames that are already late rather than queuing them.
- Media without audio switches the video sink to clock-synchronised output, so silent clips play at their own rate.
- Every flush (open, seek, track change) carries a new generation; stale frames, PCM and reports are discarded on
  both sides, and flush barriers drop samples that were decoded before the seek took effect.

## Consequences

- Measured offsets at frame arrival in the worker harness: p50 22-24 ms, p95 23-24 ms across play, seek, pause and
  resume (local Ogg, HLS over HTTP, progressive HTTP without range support), and p05 23 / p50 24 / p95 24 ms in every
  five-minute window of a 30-minute run (see test-results.md).
- The worker cannot get ahead of what was heard, so OpenAL queue depth no longer moves lip sync.
- Shared-timeline alignment between players is a separate loop (scheduled starts, clock probes, the drift
  controller) measured separately; across in-world smoke-test runs the p95 timeline error was 19-67 ms.
- Rate-based drift correction stays off until a pitch-preserving path is qualified; large drift is corrected by
  seeking after a sustained offset, with a cooldown so a slow device cannot cause a seek storm.
