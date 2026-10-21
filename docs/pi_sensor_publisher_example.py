#!/usr/bin/env python3
"""
Example publisher for the Raspberry Pi mounted on temi.

Setup on the Pi (once):
    sudo apt install mosquitto mosquitto-clients
    pip install paho-mqtt
    # let other devices on the robot's network connect (mosquitto 2.x needs this):
    echo -e "listener 1883\nallow_anonymous true" | sudo tee /etc/mosquitto/conf.d/iaq.conf
    sudo systemctl restart mosquitto

In the Android app: Settings -> Raspberry Pi Sensors -> host = the Pi's IP, port 1883,
topic = iaq/sensors  ->  Save & Reconnect.

Replace the read_* functions with your real sensor code.
"""
import json
import random
import time

import paho.mqtt.client as mqtt

BROKER = "localhost"      # the broker runs on this Pi
TOPIC = "iaq/sensors"
PERIOD_S = 2


def read_temperature():  return 24.0 + random.random()      # TODO: your temperature sensor
def read_humidity():     return 50.0 + random.random() * 5   # TODO
def read_co2():          return 450 + random.random() * 100  # TODO (ppm)
def read_pm25():         return random.random() * 20         # TODO (ug/m3)
def read_pm10():         return random.random() * 30         # TODO (ug/m3)
def read_tvoc():         return random.random() * 0.4        # TODO (mg/m3)
def read_hcho():         return random.random() * 0.04       # TODO (mg/m3)
def read_light():        return 300 + random.random() * 50   # TODO (lux)
def read_sound():        return 40 + random.random() * 5     # TODO (dB)


# paho-mqtt 2.x needs the callback API version; 1.x does not accept it.
try:
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
except AttributeError:
    client = mqtt.Client()

client.connect(BROKER, 1883, keepalive=30)
client.loop_start()

while True:
    payload = {
        "temp": round(read_temperature(), 2),
        "humidity": round(read_humidity(), 1),
        "co2": round(read_co2()),
        "pm25": round(read_pm25(), 1),
        "pm10": round(read_pm10(), 1),
        "tvoc": round(read_tvoc(), 3),
        "hcho": round(read_hcho(), 3),
        "light": round(read_light()),
        "sound": round(read_sound(), 1),
    }
    client.publish(TOPIC, json.dumps(payload))
    time.sleep(PERIOD_S)
