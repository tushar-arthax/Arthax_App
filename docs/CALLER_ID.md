# Caller ID (lead names on incoming calls)

Truecaller-style, but the only directory ever consulted is the rep's own organisation in
the ArthaX CRM. A number that is not one of the org's leads produces nothing: no card, no
"unknown caller", no lookup anywhere else.

## How a ring is named

1. **The number arrives** from one of three sources, whichever fires first on the phone:
   - `callerid/CallerIdScreeningService` — Android 10+, only while Arthax holds the
     call-screening role (`RoleManager.ROLE_CALL_SCREENING`). The system binds to it for
     every incoming call, with the number, on every OEM. It always answers "allow".
   - `call/CallStateReceiver` — the `PHONE_STATE` RINGING broadcast, which carries the
     number because `READ_CALL_LOG` is held (Android 10+ delivers it twice; the first copy
     has no number and is skipped).
   - `call/CallMonitorService`'s legacy `PhoneStateListener` on Android 11 and earlier.
2. **One lookup per ring.** `callerid/IncomingCallGate` collapses the duplicate signals
   (20 s window, reset when the line leaves RINGING).
3. **The CRM is asked**, via the same `LeadResolver` the post-call matching uses:
   `GET /api/leads/by-phone` (org-wide, matched on the trailing ten digits), falling back
   to the on-device lead cache when offline. 8 s timeout — past that the ring is over.
4. **If it is a lead**, a card is shown: name, status · company, "Arthax lead · assigned
   to X". Drawn as a floating window (`callerid/CallerIdOverlay`, needs *Display over other
   apps*); without that grant the same text is posted as a silent heads-up notification on
   the `caller_id` channel. Tap opens the lead; drag moves it off the answer button.
5. **It comes down** the moment the line goes OFFHOOK or IDLE, from any of the sources
   above, with a 90 s timeout as a backstop.

## Grants

Both are optional and asked for from Setup (step 4) and Settings → Caller ID:

| Grant | Why | Without it |
|---|---|---|
| Call-screening role | Number delivered reliably, before the ring, on every OEM | Falls back to the broadcast, which Xiaomi/Oppo drop without autostart |
| Display over other apps | Floating card over the call screen | Heads-up notification instead |

The feature has an on/off switch in Settings (on by default).

## Backend

No change. `GET /api/leads/by-phone` already answers org-wide (Back-EndCRM
`app/routers/leads.py`, `lookup_lead_by_phone`).
