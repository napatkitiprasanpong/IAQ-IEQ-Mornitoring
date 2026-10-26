# IAQ \& IEQ Monitoring for temi

An Android app (Kotlin, Jetpack Compose, Material 3) that runs on a **modified temi robot** and turns it into a mobile indoor-environment inspector.

* A **Raspberry Pi** mounted on temi reads the environmental sensors and publishes the readings over **MQTT**.
* The app shows the readings live, converts them into an **Indoor Air Quality (IAQ)** score, and plots trends.
* The app drives temi along configurable **inspection routes** (ordered lists of temi's saved locations). At each stop it samples the air and records the readings, then saves the results as a CSV file.

```
 Sensors ──► Raspberry Pi ──MQTT──►  ┌──────────────────────────┐
 (temp, humidity, CO2, PM, ...)      │  This app (on temi's     │ ──temi SDK──► temi navigation
                                     │  Android tablet)         │               (go to saved locations)
                                     └──────────────────────────┘
```

## Features

|Area|What it does|
|-|-|
|**Dashboard**|Overall IAQ gauge and level, nine sensor cards, and a Mission Control panel.|
|**Analytics**|Live gauges for every sensor.|
|**Mission**|Pick a route, start or stop it, and follow progress stop by stop with the readings taken at each one.|
|**Routine**|Daily schedule: at a set time temi drives a route, says and shows a reminder (for example "Please drink water") at each stop, and comes back later for anyone who asked for more time (see below).|
|**Camera \& Depth**|Live view of the tablet camera next to a colour-coded depth map, with distance read-outs (see below).|
|**Sign Language**|Full-screen camera that reads ASL fingerspelling (letters) and has temi say the spelled word aloud (see below).|
|**Settings**|Robot and mission behaviour, route editor, Raspberry Pi connection, editable IAQ thresholds.|
|**Startup check**|Live status of the MQTT link, data freshness, sensors reporting, battery, saved locations and storage.|
|**IAQI scale**|Reference screen with the breakpoint tables and the current values.|

**Sensors (9):** temperature, humidity, CO₂, PM2.5, PM10, TVOC, HCHO, light, sound.
**IAQ levels:** Good, Moderate, Polluted, Very Polluted, Severe.

## Requirements

* temi robot (V2: Android 6, or V3: Android 11) with the temi launcher that supports the SDK
* Raspberry Pi on the same network as the temi tablet, with an MQTT broker (Mosquitto)
* Android Studio (recent version), JDK 17+, Android SDK 36
* App: `minSdk 23`, `targetSdk 36`
* Dependencies (added in `app/build.gradle.kts`): `com.robotemi:sdk:1.138.0`, ONNX Runtime `1.17.3` (pose model for the Camera \& Depth tab), TensorFlow Lite (Sign Language tab), `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, CameraX `1.4.2` (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)

## Setup

### 1\. Save locations on temi

The app can only send temi to locations that already exist on its map. On the robot, map the area and save one location per inspection point (for example "Entrance", "Lab A"). The charging station is called **home base**.

### 2\. Set up the Raspberry Pi

```bash
sudo apt install mosquitto mosquitto-clients
pip install paho-mqtt
echo -e "listener 1883\\nallow\_anonymous true" | sudo tee /etc/mosquitto/conf.d/iaq.conf
sudo systemctl restart mosquitto
```

Then run your sensor code so it publishes to the broker. `docs/pi\_sensor\_publisher\_example.py` is a working example; replace its fake `read\_\*` functions with your real sensor readings.

> `allow\_anonymous true` is fine on a private robot network. If the network is shared, configure a username and password in Mosquitto and enter them in the app's Settings.

**Message format.** Publish JSON to the topic (default `iaq/sensors`), about every 2 seconds:

```json
{"temp":24.1,"humidity":55,"co2":620,"pm25":9,"pm10":14,"tvoc":0.2,"hcho":0.02,"light":310,"sound":42}
```

* Missing keys keep their previous value.
* Alternative key names are accepted (for example `temperature`, `rh`, `pm2\_5`, `lux`, `noise`).
* The values can also sit inside a `"sensors"` or `"data"` object.
* One sensor per topic also works: set the topic to `iaq/sensors/#` and publish a plain number to `iaq/sensors/co2`, `iaq/sensors/temp`, and so on.

Units: °C, %, ppm, µg/m³ (PM), mg/m³ (TVOC, HCHO), lux, dB.

### 3\. Build and install the app

1. Open the project in Android Studio and let Gradle sync.
2. Connect to temi with `adb connect <temi-ip>` (enable developer options on temi), or use the temi-recommended install method.
3. Run the `app` configuration. The app appears in temi's app list.

### 4\. Configure the app

Open **Settings**:

1. **Raspberry Pi Sensors:** the Pi's address is pre-filled as `10.2.115.198` (change it here if the Pi gets a different address), then check the port (1883) and topic and tap **Save \& Reconnect**. The default lives in `DEFAULT\_PI\_HOST` in `utils/AppSettings.kt`; an older saved value of `raspberrypi.local` is replaced by it.
2. **Routes \& Locations:** choose a route, then add temi's saved locations in the order they should be visited.
3. **temi Robot \& Mission:** set the sampling time per stop, the navigation timeout, retries, home base name, whether to return home at the end, and **Start this app automatically when temi starts** (see below).

#### Starting the app automatically

Switch on **Start this app automatically when temi starts** (Settings → temi Robot \& Mission). It does two things:

* It registers the app as temi's **start-up (kiosk) app** through the temi SDK (`requestToBeKioskApp`). temi may show a confirmation on its screen; accept it. Under the switch the app shows whether temi's launcher has this app selected. This is the route that also works on newer Android versions.
* It makes the app open itself when the tablet finishes booting (a boot receiver). On Android 10 and newer, Android blocks apps from opening screens in the background, so this part may do nothing there. The tablet in this project runs Android 6, where it works.

Turn the switch off to stop the boot receiver. If temi's launcher keeps opening the app, change the start-up app in temi's own settings. Test it once by restarting temi.

Check the **Startup** screen: every card should be green.

## Running a mission

1. Open **Dashboard** or **Mission**, choose a route and tap **Start Mission**.
2. For each stop, temi drives to the location and waits for arrival. If navigation fails it retries (up to the configured number), then marks the stop as failed and continues.
3. On arrival, the app waits for the sampling time, then records the IAQ score and all nine readings.
4. At the end temi optionally returns to home base.
5. **Stop Mission** cancels the current movement and ends the mission. Stops already sampled are kept.

A mission cannot start if temi is not connected, the route is empty, or a location is not saved on temi (the app tells you which one).

### Results file

Each mission writes a CSV to the app's external files folder under `missions/`, for example:

```
/sdcard/Android/data/com.leslie.iaqieqmornitoring/files/missions/mission\_20261021\_101500\_Route\_A.csv
```

The exact path is shown on the Mission screen. Columns:

`time, route, stop, location, outcome, iaq, level, temp\_c, humidity\_pct, co2\_ppm, pm25, pm10, tvoc, hcho, light\_lux, sound\_db, data\_fresh, note`

`data\_fresh` is `false` if no sensor message arrived in the 15 seconds before the reading was recorded, so stale data can be told apart from real data.

## Routine tab

The **Routine** tab sets up reminders that temi delivers in person. A routine is a **time** plus a **mission** (one of the routes from Settings). The message is "Please drink water" by default and can be changed per routine.

### How a routine runs

1. At the set time, every day, temi starts the route. (The app must be open; see the limits below.)
2. At each stop temi **speaks the message** and a **popup** appears on top of whichever tab is open, with two buttons:

   * **Yes**: temi carries on to the next stop.
   * **Come back in N mins**: temi also carries on to the next stop, and a **temp mission** is created for that stop.
3. After the last stop temi **returns to home base**. For every temp mission an **N-minute timer** now starts.
4. When a timer ends, temi drives to that one stop and asks again.

   * **Yes**: temi returns to home base and that temp mission is cleared.
   * **Come back in N mins**: temi returns to home base and a **new temp mission** with a new timer replaces it.
5. When there are no temp missions left, **the routine is done**.

`N` is 5 by default and can be changed in **Routine settings** (1 to 60 minutes), which is handy for testing.

### Using the tab

* **Add routine:** enter a name (optional), the time as `HH:MM` (24 h), pick the mission (route), and edit the message if you like. Routines have an on/off switch and can be deleted.
* **Run now** starts a routine immediately, without waiting for its time.
* **Routine status** shows what temi is doing. **Stop routine** stops temi and discards all waiting temp missions.
* **Temp missions** lists each one with its countdown ("timer starts at home base" until temi is back home). **History** logs the answers and events.
* **Popup waits for an answer** (10 to 300 s, default 60 s): if nobody taps a button in time, it counts as **Come back in N mins**, so temi never waits forever.

### Rules and limits

* The route, locations, home base, retries and navigation timeout all come from **Settings**. A routine will not start if temi is not connected, the route is empty, or a location is not saved on temi.
* temi always returns to home base at the end of a routine, whatever the "return home" setting says.
* A routine only starts if it is within 10 minutes of its time and no mission or other routine is running. It starts at most once per day. A temp mission whose timer ends during an inspection mission waits until that mission is over.
* While a routine is driving or asking, the raise-hand follow and Find person features are paused, and the Mission tab refuses to start a mission.
* If a stop cannot be reached, it is skipped and noted in the History. Nothing is retried beyond the usual navigation retries.
* Routines only run **while the app is running**: there is no background service and no start at boot. The schedule is saved, but waiting temp missions are **lost if the app is closed**.
* The popup appears inside the app. If the app is in the background, temi still speaks but no popup is visible.
* With **Simulated robot** on, the whole flow runs on any tablet; nothing is spoken.

## Camera \& Depth tab

The **Camera \& Depth** tab in the sidebar shows what temi is looking at.

* **RGB Camera (left):** live preview of the tablet's camera (front camera, or the back camera if there is no front one). Android asks for camera permission the first time the tab opens. *Flip RGB view* un-mirrors the preview.
* **Depth View (right):** a colour-coded depth map (warm = near, cool = far, dark = no reading) with a crosshair and a distance legend.
* **Read-outs (bottom):** nearest obstacle in the **Left / Ahead / Right** thirds of the image, the distance at the **Crosshair**, and the share of pixels with a valid depth reading. Distances are measured inside the band between the dashed lines, so the floor is not reported as an obstacle.

**Depth sources.** The app reads depth only from a camera that Android itself exposes as a depth sensor (Camera2 `DEPTH\_OUTPUT`, `DEPTH16` frames). If the device exposes none, the Depth View says so and shows no data. The temi SDK has no depth-stream API, and temi's own depth sensors are handled by the robot's navigation computer, so on a temi the depth panel is likely to report *Not available*. To preview the layout without a sensor, switch on **Simulated robot** in Settings: the panel then shows a clearly labelled (amber *SIMULATED*) synthetic scene.

The camera and depth stream only run while the tab is on screen and are released when you leave it or the app goes to the background.

## Raise a hand to call temi (Camera \& Depth tab)

The RGB Camera panel also runs a pose model (`app/src/main/assets/movenet\_lightning.onnx`, MoveNet Lightning, via ONNX Runtime) on the camera image and draws the detected skeleton. When a hand is raised, either a full arm stretched up or a half raise (elbow bent, forearm pointing up) and held for about 0.8 s, the badge changes to **HAND RAISED**.

* Switch on **Raise hand → follow me** (top right of the tab). When a raised hand is seen, temi says hello and starts its built-in follow mode (`beWithMe`), which keeps its own safe distance and avoids obstacles. It stops after 30 s, when you press **Stop following**, or when you leave the tab.
* It is **off by default**, ignores raised hands while a mission is running, and waits 10 s between triggers.
* The wrist must be clearly above the shoulder line and above its elbow. Hands at chest height, hands on hips, a T-pose and arms down do not count. Either hand works.
* Works best 1-3 m from the robot with the upper body in view. Pose detection only runs while the tab is open.
* With **Simulated robot** on, the app shows the FOLLOWING state without moving anything, so the gesture can be tested on any tablet or phone.
* **Walk to me (on by default, toggle "Walk to me first"):** temi's own follow mode finds people with its depth sensors, so it only works at close range. With *Walk to me first* on, a raised hand makes temi walk to you using only the tablet camera, and only then (optional) start follow mode:

  1. temi says "I'm coming" and, twice a second, estimates your distance from the width of your shoulders in the picture and your direction from where you are in the frame.
  2. If you are more than about 10° off to one side it turns towards you (`turnBy`), otherwise it drives forward with temi's obstacle avoidance (`skidJoy` with *smart* on).
  3. It stops at the **Stop at** distance (1.0, 1.3, 1.6 or 2.0 m; tap the pill to change) and says "I'm here". It then starts follow mode for the usual 30 s, which works at that range. Switch this off with `approachThenFollow` in `AppSettings.kt` if you only want temi to walk up and wait.
  4. It gives up if it cannot see you for 4 s, after 60 s, when a mission or routine starts, or when you make the **T-pose**.
  * **Calibrate once:** stand exactly 2 m from temi, facing it with both shoulders visible, and tap **Calibrate (stand 2 m away)**. The pill row also shows a live "You: 2.0 m, 5° left" read-out to check it.
  * **If temi turns away from you,** switch on **Invert turn**.
  * **Range:** it is limited by how far the pose model can see you, roughly 3-4 m in a normal room (shoulders only a few pixels wide beyond that). Distance is an estimate: someone standing sideways looks closer than they are, and children or very broad people will be misjudged.
  * **First test:** clear floor, someone at the robot's Stop button, and the T-pose ready. Simulated robot mode only pretends to drive for 2.5 s.
* **Stop gesture (T-pose):** stretch both arms straight out to the sides at shoulder height and hold for about 0.8 s. temi says it will stop and ends follow mode (works even if *Raise hand → follow me* is off). The skeleton turns red and the badge shows **STOPPING**. A bent arm, one arm out, arms up or arms down do not count.
* **Find person:** switch on **Find person** and temi tilts its tablet slowly up and down (5° steps, about one per 1.2 s, between -15° and 45°) until the camera sees a head and both shoulders for a few frames in a row. It then stops, nudges the tilt so the head sits in the upper-middle of the picture, and stays there. If the person is gone for 4 s it starts searching again. It does not move the tablet while temi is following someone or running a mission. The badge shows **SCANNING** or **PERSON FOUND**. Off by default.

## Sign Language tab

The **Sign Language** tab (sidebar) shows the tablet camera full-screen and reads **ASL fingerspelling**: you spell a word letter by letter with one hand, and temi says the word aloud. It does **not** need MediaPipe, so it runs on Android 6 (API 23).

* **Caption bar (bottom):** the letters spelled so far (large), the top guesses for the letter being shown, a hold progress message ("Hold C... 60%"), and the last few finished words. A badge (top left) shows `READY`, `SIGNING`, `LOADING MODEL` or `ERROR`. A green square on the picture shows the part of the image that is given to the model.
* **Speak: ON/OFF (top right):** whether temi says the word. With *Simulated robot* on, nothing is spoken but the caption still works.
* **Flip hand (top right):** mirrors the hand picture before it is classified. Most alphabet datasets show a right hand; if you sign with your left hand, or letters come out as mirror images, switch it on.
* **Input 0..1 / -1..1 / 0..255 (top right):** how the model expects pixel values. A .tflite file does not say how its training images were scaled, so tap the pill until the guesses make sense (most models use `0..1` or `-1..1`; a model that rescales inside itself uses `0..255`). Models with `uint8` input ignore it. The choice is remembered.

### How a word is spelled

1. Raise a hand (wrist above the hips) and show a letter. The pose model finds the wrist and elbow, and a square is cut out just past the wrist.
2. The letter is added once it has been shown steadily for 0.8 s at 70% confidence or more. The same letter twice in a row (for example the two L in "HELLO") needs a short pause or a 1.5 s hold.
3. Lower your hand for about 1.8 s, or show the model's `space` sign, and the word is finished: it is shown and temi says it. A `del` sign removes the last letter.

**Scope:** American Sign Language **letters** only. J and Z need movement and will not be recognised properly by a single-picture model. No whole-word signs, no sentences, and no Thai or other sign languages. Alphabet models are usually trained on clean photos, so expect lower accuracy in a real room: good light, a plain background, hand about 1 m away and held still.

```
camera frame -> MoveNet pose (same ONNX model as the Camera \& Depth tab) -> wrist + elbow of the raised hand
             -> square crop past the wrist, scaled to the model's input size
             -> asl\_alphabet.tflite -> letter
             -> hold 0.8 s -> letters -> word -> caption + temi.speak()
```

Code: `screens/SignLanguageScreen.kt` (UI), `utils/SignRecognizer.kt` (hand crop, letters to words, speaking), `utils/SignClassifier.kt` (TensorFlow Lite).

### Model files

`app/src/main/assets/` must contain:

|File|What it is|
|-|-|
|`movenet\_lightning.onnx` (9.4 MB)|pose model (Camera \& Depth tab and hand finding here). Already in the project.|
|`asl\_alphabet.tflite`|**you add this**: an ASL alphabet image classifier.|
|`asl\_alphabet\_labels.txt`|optional: one label per line, in the model's output order. (The name must not end in `.txt.txt`; Windows hides the first `.txt`.)|

Requirements for the alphabet model: input `\[1, height, width, 3]` (RGB) or `\[1, height, width, 1]` (grayscale, e.g. 64x64), float32 or uint8, output `\[1, classes]` (probabilities or logits, float or quantised). Size and types are read from the file. Without the labels file, **26 outputs are read as A-Z**, **27 as A-Z + nothing** and **29 outputs as A-Z, del, nothing, space** (the order of the Kaggle "ASL Alphabet" dataset); any other size needs the labels file. Label names `nothing`/`blank`/`none`/`background` are ignored, `space` ends a word and `del`/`delete` removes a letter. If the file is missing the tab says so.

If the letters look wrong, check, in this order: **Input** mode, **Flip hand**, and the order of the labels.

### Tuning

The thresholds are constants at the top of `utils/SignRecognizer.kt` (`LETTER\_THRESHOLD`, `HOLD\_MS`, `REPEAT\_MS`, `WORD\_GAP\_MS`, ...). The on-screen `fps · ms` line shows the real speed (pose model plus classifier).

## IAQ score

Each sensor has four breakpoints that define its five quality bands. Each reading is converted to a 0–100 score, and the **overall IAQ score is the average of the nine scores**. The breakpoints can be edited in **Settings → IAQ Thresholds** (with a reset to defaults).

Default breakpoints:

|Sensor|B1|B2|B3|B4|
|-|-|-|-|-|
|Temperature (°C)|18|25|30|35|
|Humidity (%)|30|50|70|85|
|CO₂ (ppm)|400|600|1000|2500|
|PM2.5 (µg/m³)|12|35|55|150|
|PM10 (µg/m³)|20|50|100|200|
|TVOC (mg/m³)|0.3|0.5|1.0|3.0|
|HCHO (mg/m³)|0.03|0.05|0.10|0.20|
|Light (lux)|100|300|500|1000|
|Sound (dB)|30|50|70|90|

## Testing without hardware

In **Settings** you can switch on:

* **Simulated robot:** a fake temi with sample locations (Entrance, Lab A, Lab B, Corridor, home base). Missions run end to end.
* **Simulated sensors:** random values every 2 seconds, with no Pi needed.

The startup screen shows simulated sources in amber so they cannot be mistaken for real ones. Switch both off for real use.

## Project structure

```
app/src/main/java/com/example/iaqieqmornitoring/
├── MainActivity.kt            starts settings, temi and sensor links
├── navigation/                screen routes
├── screens/                   Startup, Dashboard, Analytics, Detail, IAQI scale, Mission, Routine, Camera \& Depth, Sign Language, Settings
├── components/                gauges, cards, top bar, sidebar, shared form widgets, RoutinePromptHost (the routine popup)
└── utils/
    ├── AppSettings.kt         persisted settings and routes
    ├── ThresholdStore.kt      editable IAQ breakpoints
    ├── SensorRepository.kt    current readings, link state, simulator
    ├── PiSensorClient.kt      MQTT client and payload parser
    ├── TemiRepository.kt      the only class that uses the temi SDK (plus simulated robot)
    ├── DepthCameraSource.kt   depth stream (Camera2 DEPTH16 or simulated), colouring and distance stats
    ├── MissionManager.kt      mission engine and CSV export
    ├── RoutineManager.kt      routine scheduler, popup flow and temp missions (Routine tab)
    ├── GestureEngine.kt       camera -> pose -> raised hand / T-pose / find person -> temi
    ├── ApproachController.kt  walks temi to a person using the tablet camera (distance from shoulder width)
    ├── PoseDetector.kt        MoveNet Lightning (ONNX Runtime)
    ├── GestureDetectors.kt    raised-hand and T-pose detectors
    ├── SignRecognizer.kt      hand crop, letters to words, speaking (Sign Language tab)
    ├── SignClassifier.kt      TFLite alphabet classifier
    ├── StartupChecks.kt       startup self-checks
    └── IAQCalculator.kt / IAQConfig.kt   scoring
docs/pi\_sensor\_publisher\_example.py
```

## Troubleshooting

|Symptom|Check|
|-|-|
|Startup: "MQTT Connection" red|Pi's IP/port correct? Mosquitto running and listening on 1883 for other hosts? Tablet and Pi on the same network?|
|MQTT connected but "Sensor Communication" red|The Pi is not publishing to the configured topic. Test with `mosquitto\_sub -h <pi-ip> -t 'iaq/#' -v`.|
|MQTT connected, sensors red|Settings → Raspberry Pi Sensors now shows how many messages arrived, the last topic and payload, and why a message was rejected. The app listens to both `<topic>` and `<topic>/#`.|
|"Air Quality Sensors" amber, some missing|The JSON is missing those keys or uses unrecognised names.|
|"Robot Navigation" red|The app is not running on a temi, or the temi SDK is not connected. Use the simulated robot for testing elsewhere.|
|"Robot Navigation" amber: no saved locations|Save locations on temi, then use Settings → Refresh locations from temi.|
|Mission refuses to start: "Not saved on temi: …"|A route contains a location name that does not exist on the robot's map. Re-pick it in Settings.|
|Camera \& Depth: RGB panel says "No permission"|Allow Camera for the app in Android Settings > Apps, then reopen the tab.|
|Camera \& Depth: Depth View says "Not available"|The device exposes no depth camera to apps (expected on temi). Use *Simulated robot* to preview the view.|
|Camera \& Depth: RGB shows "too many cameras open"|The device cannot run two cameras at once, or another app holds the camera. Close other camera apps.|
|Stops fail with "timed out"|Raise the navigation timeout, or check for obstacles or a lost map on temi.|

## Known limitations

* The overall IEQ value on the dashboard is currently `(temperature + humidity) / 2`; it is not a standard comfort index.
* Temperature and humidity are scored as "middle is best", and the score is not continuous at the second breakpoint. Light and sound are scored as "middle is best" in the overall IAQ but shown as "lower is better" on the sensor cards.
* The app does not keep a long-term history of readings; the mission CSV files are the only saved record.
* Routines only run while the app is open, and waiting temp missions are not saved (see the Routine tab section).
* temi's follow mode (`beWithMe`) relies on the robot's own person detection, which only works at close range. That is why *Walk to me first* brings temi close before handing over to it.
* *Walk to me* judges distance from shoulder width in a single camera, so it is approximate and cannot see beyond a few metres. The first time, check which way temi turns (Invert turn) and calibrate.
* The sensor connection is not encrypted (plain MQTT on a local network).
* Test the first missions and routines with the robot in a clear area and keep the Stop button within reach.

