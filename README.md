# TV Drop
**Open-source, private, and fast file transfer app between Android TV devices and Android phones.**

![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Android-blue)
![Tech](https://img.shields.io/badge/Tech-Kotlin%20%7C%20Jetpack%20Compose%20%7C%20Nearby%20%7C%20mDNS-orange)
![License](https://img.shields.io/badge/License-GPLv3-blue)
![Status](https://img.shields.io/badge/Status-Active%20Development-yellow)

---

## 📌 Overview

**TV Drop** is a modern and privacy-focused file transfer application designed specifically for  
**Android TV devices and Android phones.**  
It enables **offline, fast, and secure** bidirectional file transfers using both **Nearby API** and **LAN-based TCP streaming** — all without ads, accounts, servers, or cloud dependencies.

---

## ⭐ Key Features

| Feature | Status |
|--------|--------|
| Phone → TV & TV → Phone file transfer | ✔️ |
| Offline discovery using Google Nearby | ✔️ |
| High-speed transfer over LAN (TCP) | ✔️ |
| Trusted devices & PIN verification | ✔️ |
| Modern Jetpack Compose UI | ✔️ |
| Android TV remote-friendly UX | ✔️ |
| Transfer history (IN & OUT) | ✔️ |
| No ads, no analytics, no tracking | ✔️ |
| Background transfer service | 🚧 Planned |
| Cross-platform expansion | 🧪 Research |

---

## 🛠️ Tech Stack

- **Kotlin**
- **Jetpack Compose (Phone & TV)**
- **Android TV DPAD navigation**
- **Nearby Connections API**
- **mDNS service discovery**
- **Direct TCP chunked streaming**
- **Scoped Storage (SAF)**

---

## 📥 Installation

This project contains **two independent modules**:
/app-tv       → Android TV module
/app-phone    → Android phone module
/core         → Shared models, utilities, protocol constants

---

### Build manually

```bash
git clone https://github.com/your-username/tv-drop.git
open the project in Android Studio
select module → Run
```
APK files will also be published in the Releases tab once ready.

---

## 🔐 Privacy

TV Drop does not use internet or cloud services.
Your files never leave your local network or device.
- No ads
- No logs
- No telemetry
- No analytics
- No tracking
- No third-party data sharing

---

## 🤝 Contributing
1.	Fork the repository
2.	Create a feature branch
3.	Commit and push your changes
4.	Submit a pull request

All meaningful contributions are appreciated:
code, UI/UX, documentation, translations, testing, feature ideas, bug reports, optimization.

---

## 📄 License

This project is licensed under the GNU General Public License v3 (GPL-3.0).
See LICENSE￼ for full details.

---

## ⭐ Support

If you like this project, please consider starring ⭐ the repository.
It helps visibility and encourages ongoing development.

---

## 👤 Author

Mahmut Alperen Ünal
Android Developer