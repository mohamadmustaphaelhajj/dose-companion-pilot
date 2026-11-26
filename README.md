# Dose Companion

A medication journal for Android, with a planned Windows web companion. Designed to help people check what they confirmed taking and when.

## Active workstream: personal pilot

This checkout now targets [`dose-companion-pilot`](https://github.com/mohamadmustaphaelhajj/dose-companion-pilot), the personal pilot. The future public product remains in [`dose-companion`](https://github.com/mohamadmustaphaelhajj/dose-companion). Both repositories contain public source code, not personal medical records or deployment secrets.

Prioritize the pilot until it is ready for the intended user. Public-product hosting, billing and wider distribution are deferred. See [the repository/workstream guide](docs/workstreams.md) for remotes, shared fixes and the planned independent-phone milestone.

The [hosted pilot package](docs/hosted-pilot.md) prepares Render deployment, phone pairing and the external warm-up schedule. Deployment and cable-free acceptance remain pending; this does not replace the working USB bridge until configured and tested.

**Status: development preview with cloud label reading.** Capture two to four separate photos and ask OpenAI to read them together. Review and explicitly confirm every returned field before saving. See [API setup, privacy and testing](docs/cloud-label-reader.md). The medication journal and reminders work locally; ingestion detection and account/history sync are not implemented.

Local-model, stitching and panorama research is preserved on [`feature/label-reader-experiments`](https://github.com/mohamadmustaphaelhajj/dose-companion/tree/feature/label-reader-experiments).

## Implemented in this milestone

- Add a medication's name, strength, quantity per dose, daily times, and reminder priority.
- See today's scheduled doses, in chronological order.
- Explicitly confirm a dose as taken, or record uncertainty without marking it taken.
- Review history with the scheduled date/time and the actual confirmation timestamp.
- Archive a schedule without deleting its history.
- Save the journal in an app-private SQLite database. The cloud-enabled build has network permission; offline-only builds remove it.
- Reject duplicate taken entries for the same scheduled dose, including at the database level.
- Enable local reminders, snooze, priority-based follow-ups and recovery after restart/time changes.
- See notification/alarm permission status and stop reminders by resolving, dismissing or archiving a dose.
- Upgrade the database without losing existing medication history.
- Open a live rear-camera view for an unresolved dose and match its bottle label against the saved name and strength.
- Scan and explicitly confirm the name, strength and directions in sequence; set quantity and schedule separately.
- Review live OCR readings and field boxes at each step. No step advances automatically, and scanning never writes taken records.

The journal contains user confirmations, not medical verification of ingestion. Priority controls a bounded reminder cadence, never medication amounts or instructions about taking an additional dose.

## Development

The Android app uses Java, CameraX and bundled ML Kit OCR. The cloud reader sends selected photos through a Python backend to OpenAI; the API key stays on the backend. Local-model weights, OpenCV, LiteRT-LM and the native C++ inference toolchain are not required on main. Photos remain in memory in the app/backend; provider retention is disclosed before upload.

### Run core and storage checks

Requires JDK 17 or newer and Python 3:

```sh
bash scripts/check.sh
```

Checks cover scheduling around enrollment, midnight, time zones and daylight saving; explicit confirmation and repeated actions; and the actual SQLite schema's persistence, uniqueness and history-preservation constraints. A separate isolated emulator suite exercises notifications and Android's SQLiteOpenHelper lifecycle; see [reminder testing](docs/reminder-testing.md).

Verified on November 19, 2026: 34 core checks, 11 SQLite/resource checks and 7 emulator integration tests passed. The normal APK build and lint passed using JDK 21. A real local alarm was delivered with the test process closed and emulator connectivity disabled; the network settings were restored afterward. Reboot recovery also passed, with an observed startup/delivery delay of about three minutes on the API 37 emulator. Physical-phone testing remains outstanding.

### Build Android

Requires Android Studio, JDK 17 or 21, and Android SDK platform 35. The project uses Android Gradle Plugin 8.13.0 and Gradle 8.13. Minimum Android version: API 26. No NDK, CMake or local model download is needed.

Open this directory in Android Studio and select the `app` run configuration. The official Gradle wrapper is included, with its distribution checksum pinned. From a terminal with the Android SDK configured:

```sh
./gradlew :core:verifyCore :app:assembleDebug :app:lintDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The default build enables the cloud photo reader. Use `-PcloudExperiment=false` for an offline-only build. The first build downloads Gradle and build dependencies; the installed app's local journal does not need internet. A debug APK will appear at `app/build/outputs/apk/debug/app-debug.apk` after a successful build.

Do not check `local.properties`, signing keys, personal labels, medication records, credentials, or captured media into GitHub.

For the current cloud experiment, build with `-PcloudExperiment=true` to install **Dose Companion Lab** alongside the regular app and preserve its journal. Follow the [cloud reader guide](docs/cloud-label-reader.md) for API setup and USB pairing. The cloud reader does not require imported local weights.

## Schedule behavior

- Daily schedules retain the device's time zone at creation. Travel does not silently move the schedule to another time zone.
- Doses before schedule enrollment are not created retroactively.
- Each scheduled date and time has one identity, even during a repeated daylight-saving hour. Nonexistent spring-forward times move forward through the gap. These semantics will need review before reminder release.
- Confirming ahead of the scheduled time requires an explicit dialog; the actual confirmation time is recorded separately.
- Unsure entries remain in the history when a later confirmation resolves the dose.
- Uninstalling the app removes local history. Device/cloud backup is disabled in this preview. Export and account synchronization are later milestones.

## Next milestones

1. **Guided action tracking:** hand and face landmarks plus a temporal sequence detector for hand-to-mouth gestures. Gesture detection does not establish pill ingestion or swallowing. The user explicitly confirms completion.
2. **Account and Windows companion:** authenticated access, history sync, offline queues and visible sync freshness, including conflicting offline entries for the same dose.
3. **Portfolio demonstration:** optional CV overlays and measured recognition/gesture performance on consented or synthetic development data. The app will not persist users' camera frames.

Reminder setup, limits, validation and the reminder-only staging checkpoint are documented in [reminder testing](docs/reminder-testing.md).

See [the project decisions](docs/project-decisions.md) for the intended camera flow and privacy rules.

## Version control

Working name: `dose-companion`. Intended visibility: public. No repository or commit is created automatically. Every commit requires the project owner's explicit approval after reviewing the proposed changes.

## Try the sequential scanner

Choose **Add medication → Scan name, strength and directions**, or **Check bottle label** on an unresolved dose.

1. Point at the **name**. The app shows live OCR boxes and its reading. Tap **Read and review this field**, compare/correct it against the bottle, then **Confirm and next**.
2. Repeat for **printed strength**. A reading such as `25 mg` is never silently converted to the expected `2.5 mg`; existing saved details must match your confirmed value.
3. Point at **dosage directions**. If the text curves around the bottle, use **Add visible directions section**, rotate, and add another section. Review the joined wording for missing or duplicated words before confirming.

The summary returns reviewed fields to the setup form, or explicitly saves reviewed directions for an existing medication. Enter quantity and reminder times separately; the scanner does not calculate tablets per day or infer a schedule. Scanning never writes a taken-dose event.

**Rescan this step** keeps earlier confirmations. **Start again with a different bottle** clears all steps. Confirmed text survives rotation; unconfirmed OCR, partial sections and the retained camera frame are discarded when leaving the screen. No images are saved or uploaded, and no VLM/model download is needed by this workflow.

Directions are saved only after explicit confirmation, in database version 3. The migration preserves medication, reminder and dose-event tables. Real rounded-bottle testing is still necessary.

The local-model and panorama research notes are preserved on [`feature/label-reader-experiments`](https://github.com/mohamadmustaphaelhajj/dose-companion/tree/feature/label-reader-experiments).
