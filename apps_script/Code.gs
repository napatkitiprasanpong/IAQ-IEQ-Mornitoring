/**
 * IAQ-IEQ data receiver (Google Apps Script web app).
 *
 * What it does
 *   - Keeps ONE master spreadsheet named "iaq-ieq data" inside a Google Drive folder named "iaq-ieq data".
 *     Both are created automatically the first time they are needed.
 *   - The temi app POSTs the readings of every IAQ-IEQ routine run; this script appends them as new rows
 *     to the "data" sheet (date, time, location, interval_s, duration_s, sensors, iaq, ...).
 *   - A hidden "_uploads" sheet remembers which chunks were already received, so a retry after a
 *     network error never creates duplicate rows.
 *
 * Setup (once)
 *   1. Go to https://script.google.com -> New project. Paste this whole file over Code.gs.
 *   2. Change SECRET below to a long random string. Put the SAME string in the app
 *      (Routine tab -> Google Sheets upload -> Secret token).
 *   3. Select the function "setup" at the top and press Run. Accept the permission prompts
 *      (Drive + Sheets). The log shows the link to your spreadsheet.
 *   4. Deploy -> New deployment -> type "Web app".
 *        Execute as: Me        Who has access: Anyone
 *      Copy the "Web app" URL (ends in /exec) into the app (Routine tab -> Apps Script web app URL).
 *   5. In the app press "Test connection".
 *
 *   After changing this script later, use Deploy -> Manage deployments -> edit -> New version,
 *   otherwise the old code keeps running.
 *
 * Security note: "Anyone" means anyone who has the URL can call it, so the SECRET is the only lock.
 * Keep both private.
 */

const SECRET = 'change-me-to-a-long-random-string';

const FOLDER_NAME = 'iaq-ieq data';
const SPREADSHEET_NAME = 'iaq-ieq data';
const DATA_SHEET = 'data';
const LOG_SHEET = '_uploads';

// Must match IaqDataStore.HEADER in the Android app.
const HEADER = [
  'date', 'time', 'location', 'interval_s', 'duration_s',
  'temp_c', 'humidity_pct', 'co2_ppm', 'pm25', 'pm10', 'tvoc', 'hcho', 'light_lux', 'sound_db',
  'iaq', 'level', 'data_fresh', 'routine', 'run_id'
];

/** Run this once by hand to create the folder + spreadsheet and grant permissions. */
function setup() {
  const ss = getSpreadsheet_();
  Logger.log('Ready: ' + ss.getUrl());
}

function doGet() {
  return json_({ ok: true, message: 'IAQ-IEQ endpoint is up' });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const req = JSON.parse(e.postData.contents);
    if (req.token !== SECRET) return json_({ ok: false, error: 'wrong token' });

    if (req.action === 'ping') {
      return json_({ ok: true, spreadsheet: getSpreadsheet_().getUrl() });
    }

    if (req.action === 'append') {
      lock.waitLock(30000);
      const ss = getSpreadsheet_();
      const log = ss.getSheetByName(LOG_SHEET);
      const key = String(req.key || '');

      // Already received this chunk (the app retried after a timeout)? Then do nothing.
      if (key && log.createTextFinder(key).matchEntireCell(true).findNext()) {
        return json_({ ok: true, duplicate: true, rows: 0 });
      }

      const rows = (req.rows || []).map(function (r) {
        const row = r.slice(0, HEADER.length);
        while (row.length < HEADER.length) row.push('');
        return row;
      });
      if (rows.length) {
        const sheet = ss.getSheetByName(DATA_SHEET);
        sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, HEADER.length).setValues(rows);
      }
      if (key) log.appendRow([key, new Date(), rows.length]);
      return json_({ ok: true, rows: rows.length });
    }

    return json_({ ok: false, error: 'unknown action' });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  } finally {
    if (lock.hasLock()) lock.releaseLock();
  }
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function getFolder_() {
  const it = DriveApp.getFoldersByName(FOLDER_NAME);
  return it.hasNext() ? it.next() : DriveApp.createFolder(FOLDER_NAME);
}

/** The master spreadsheet, created inside the Drive folder on first use. */
function getSpreadsheet_() {
  const props = PropertiesService.getScriptProperties();
  let ss = null;

  const id = props.getProperty('SPREADSHEET_ID');
  if (id) {
    try { ss = SpreadsheetApp.openById(id); } catch (e) { ss = null; }
  }
  if (!ss) {
    const folder = getFolder_();
    const files = folder.getFilesByName(SPREADSHEET_NAME);
    if (files.hasNext()) {
      ss = SpreadsheetApp.open(files.next());
    } else {
      ss = SpreadsheetApp.create(SPREADSHEET_NAME);
      DriveApp.getFileById(ss.getId()).moveTo(folder);
    }
    props.setProperty('SPREADSHEET_ID', ss.getId());
  }
  ensureSheets_(ss);
  return ss;
}

function ensureSheets_(ss) {
  let data = ss.getSheetByName(DATA_SHEET);
  if (!data) {
    data = ss.getSheets()[0];
    data.setName(DATA_SHEET);
  }
  if (data.getLastRow() === 0) {
    data.appendRow(HEADER);
    data.setFrozenRows(1);
  }
  if (!ss.getSheetByName(LOG_SHEET)) {
    ss.insertSheet(LOG_SHEET).hideSheet();
  }
}
