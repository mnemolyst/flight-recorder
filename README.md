Flight Recorder
===============

Flight Recorder is an Android app that functions like a dashcam with automatic features for cyclists and motorcyclists.

It is primarily a video recorder.

It maintains a FIFO (first in, first out) queue of encoded video frames and audio.

It monitors the gravity sensor (or accelerometer) for a tip-over. If this happens, assuming the user has crashed their bike, the saved video frames (most recent 5 to 60 seconds) are synced with the audio and dumped to an MP4 file.

Optionally, the file is tagged with the device's location.

Optionally, if the user has connected a Google Drive account, the app attempts to copy the file there.
