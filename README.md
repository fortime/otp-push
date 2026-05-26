# Project Summary

OTP Push is a system designed to securely transmit One-Time Passwords (OTPs) between devices. It enables a "Request-Response" flow where one device can request an OTP and another (remote) device provides it, facilitating easy auto-filling on the target device. It is particularly integrated with [fcitx5-osk](https://github.com/fortime/fcitx5-osk) to allow for automatic OTP typing.

## 🔄 Core Flow

1. Initiation: A user initiates an OTP request for a specific service (e.g., GitHub, Banking).
2. Notification: The Server receives the request and pushes a real-time notification via Firebase Cloud Messaging (FCM) to all other devices registered to that user.
3. Remote Submission: A user on a Remote Device (who has access to the OTP, e.g., via Keepass2Android) receives the notification, enters the code, and submits it back to the
  server.

## Running the Server Container

The server image is available at:

```text
ghcr.io/fortime/otp-push/otp-push-server
```

Create a `config.toml` file before starting the container:

```toml
listen_addr = "127.0.0.1:3000"
base_url = "https://domain/path"
google_client_id = "xxx"
jwt_secret = "yyy"

# Needed for FCM, optional
fcm_service_account = "fcm.json"
```

The config file should be mounted at `/home/app/config.toml`. To keep the server
database across container restarts, mount a host directory at `/home/app/data`.

Example with a rootless Podman container:

```sh
podman run --rm \
  -p 3000:3000 \
  -v ./config.toml:/home/app/config.toml:U \
  -v ./data:/home/app/data:U \
  ghcr.io/fortime/otp-push/otp-push-server
```

The data volume is optional. If it is omitted, the database will not be
persistent.

## Android App and FCM

The APK attached to GitHub releases is built with the FCM configuration for the
maintainer's Firebase project. If you self-host the server and want FCM push
notifications to work with your own Firebase project, build a new APK with your
own FCM configuration.
