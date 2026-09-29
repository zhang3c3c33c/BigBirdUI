# BBUI Calendar tool contract

The Android agent exposes `system_calendar` through the existing authenticated `/system` bridge and Shizuku UserService. It uses Calendar Provider under the current Android user, never GUI automation or arbitrary model-supplied shell/SQL. The desktop backend does not advertise this capability until implemented.

## Operations

- `calendars`: list calendars and provider capabilities, with pagination and current device time/time zone.
- `list`: query occurrences in the required `startMs`/`endMs` interval via Instances, optionally restricted by `calendarId` and literal `query` text; paginate.
- `details`: read one `eventId` and its reminders.
- `create`: require `calendarId`, `title`, `startMs`, `endMs`, `timeZone`; optional `description`, `location`, `allDay`, standard `rrule` and `reminderMinutes`.
- `update`: require `eventId` and `scope: "series"`; change only provided fields. Omitted reminders/recurrence retain their existing values, `reminderMinutes: []` clears reminders and `rrule: null` cancels recurrence.
- `delete`: require `eventId` and `scope: "series"`; delete the selected complete event or series. Single-occurrence and "this and following" edits are not advertised in this version.

All operations accept an optional `userId` only when equal to the actual current Android user. IDs identify provider rows; the model must not invent them. Creation defaults to a timed, non-repeating event with no reminders. All-day dates use the Calendar Provider UTC-midnight convention and an exclusive end. Recurrence uses standard RRULE plus provider duration, rather than generating independent events or a new recurrence engine. Notifications use the calendar's supported reminder methods and maximum reminder count.

## Execution facts

Reuse STOP/takeover, run identity, serialization, action deduplication and uncertainty handling. Calendar access never creates a virtual screen. Provider writes and associated reminders are batched where possible. Retain a bounded receipt containing the target/created event identity independently of long result data, including for duplicate requests and post-write readback failure. Never retry an uncertain creation automatically.

Tools execute explicit operations and return facts. The model decides whether a missing target calendar/time/recurrence choice requires a question from the current task and prior preferences; there is no tool-level approval workflow. A stored reminder is not proof that a notification has actually appeared or sounded.

## Presentation and acceptance

The model receives structured results; chat receives model-authored intent plus a bounded summary/status, never the raw event inventory or description body. Failures remain failed in Pi's real loop while retaining dispatch receipts.

Use synthetic loopback providers for three-protocol tests. Device tests create only uniquely tagged, future temporary events, verify recurrence instances/reminder rows/update/dedup/STOP/deletion, and remove only their own IDs. Existing event 536 and user events must remain unchanged. Only the main agent installs and runs device acceptance. Real model/search API calls are not authorized by this implementation task.
