# Project Summary

OTP Push is a system designed to securely transmit One-Time Passwords (OTPs) between devices. It enables a "Request-Response" flow where one device can request an OTP and another (remote) device provides it, facilitating easy auto-filling on the target device. It is particularly integrated with [fcitx5-osk](https://github.com/fortime/fcitx5-osk) to allow for automatic OTP typing.

## 🔄 Core Flow

1. Initiation: A user initiates an OTP request for a specific service (e.g., GitHub, Banking).
2. Notification: The Server receives the request and pushes a real-time notification via Firebase Cloud Messaging (FCM) to all other devices registered to that user.
3. Remote Submission: A user on a Remote Device (who has access to the OTP, e.g., via Keepass2Android) receives the notification, enters the code, and submits it back to the
  server.
