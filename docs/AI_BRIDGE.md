# AI / voice bridge

Lets the MatePet voice app (VRM avatar + local AI on temi) read this app's data and control it.

| Channel | Direction | Detail |
|---|---|---|
| Broadcast `com.leslie.iaqieqmornitoring.action.COMMAND` | MatePet -> this app | extras `cmd`, `arg` |
| Content provider `content://com.leslie.iaqieqmornitoring.bridge/status` | this app -> MatePet | one row, column `json` |

Commands: `START_MISSION` (route name, optional), `STOP_MISSION`, `START_ROUTINE` (routine name, optional), `STOP_ROUTINE`, `OPEN_TAB` (dashboard, analytics, mission, routine, settings, startup, sign language, camera and depth).
Every command sets a spoken reply (`message`, with a rising `msgId`), e.g. the refusal "Not saved on temi: Lab A".

Status JSON: `iaq, level, fresh, sensors{temp,humidity,co2,pm25,pm10,tvoc,hcho,light,sound}, mission{state,route,stop,total}, routine{state,phase}, routes[], msgId, message`.

Security: the receiver and provider require the signature permission `<applicationId>.permission.BRIDGE`. Install this app first, then MatePet, signed with the same key (debug builds from the same computer share one). Code: `bridge/`.
