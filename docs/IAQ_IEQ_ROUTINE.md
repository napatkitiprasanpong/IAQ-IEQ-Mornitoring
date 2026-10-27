# IAQ-IEQ routine (Routine tab) and Google Sheets upload

## What it does
The Routine tab now has two routine types:

* **Free style routine**: the original reminder routine (speak a message, popup, come back later).
* **IAQ-IEQ routine**: temi drives the route; at each stop it collects sensor readings for a chosen time,
  saves them on the tablet, then drives to the next stop. After the last stop temi returns to home base and
  the data is uploaded to Google Sheets.

### Creating an IAQ-IEQ routine
Routine tab -> Add routine -> Type: IAQ-IEQ routine -> choose the time and the route. For **each stop** pick:

| Collect for | Reading every |
|---|---|
| 5 sec, 30 sec, 1 min, 5 min | 1 sec, 5 sec, 30 sec, 1 min (never longer than "collect for") |

The first reading is taken one interval after temi arrives and the last when the time is over:
"30 sec every 5 sec" = 6 readings in 30 seconds. No popup and no spoken reminder in this routine type.

Note: the Raspberry Pi example publishes about every 2 seconds, so a 1 sec interval repeats values.
If you want true 1 sec data, make the Pi publish every 1 second (PERIOD_S in docs/pi_sensor_publisher_example.py).

## Local files
Written stop by stop, so nothing is lost if the routine is stopped:

```
<app files>/iaq_routines/pending/    waiting for upload (or upload failed)
<app files>/iaq_routines/uploaded/   uploaded successfully
```
e.g. `/sdcard/Android/data/com.leslie.iaqieqmornitoring/files/iaq_routines/pending/iaq_20261028_081500_IAQ_IEQ_08_15.csv`

## Google Sheets setup (once)
See the instructions at the top of `apps_script/Code.gs`. In short: paste it into a new Apps Script project,
set `SECRET`, run `setup`, deploy as Web app (Execute as: Me, Access: Anyone), then in the app go to
Routine tab -> Google Sheets upload, paste the URL and the same secret, press **Test connection**.

Result in your Google Drive:

```
iaq-ieq data/                 (folder)
  iaq-ieq data                (one master spreadsheet)
     data sheet   date | time | location | interval_s | duration_s | temp_c | humidity_pct | co2_ppm | pm25 | pm10 | tvoc | hcho | light_lux | sound_db | iaq | level | data_fresh | routine | run_id
```
Every run appends new rows to the bottom. `data_fresh` is `false` when no sensor message arrived in the
previous 15 seconds.

## If the upload fails
The CSV stays in `pending/`. The Routine tab shows how many files are waiting and has an
**Upload pending now** button (disabled while a routine is running). Retries never duplicate rows.
