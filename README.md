# Pocket Hub

### Advanced System Control Center for AYANEO Pocket DS

**Take full control of your AYANEO Pocket DS.**

Pocket Hub is an advanced system management and control application designed specifically for the **AYANEO Pocket DS** running Android 13.

It aims to replace the limitations of the stock AYANEO control interface with a more flexible, powerful and customizable control center, providing direct access to system-level hardware controls, performance management and dual-screen functionality.

Pocket Hub brings essential device controls together in one convenient interface, reducing the need to navigate through Android settings, manufacturer-specific menus or external utilities.

---

## ⚠️ Important: Root Access Required

**Pocket Hub requires ROOT access to function correctly.**

This application interacts directly with Android system services and hardware interfaces that are normally restricted by Android.

Root permissions are required for features such as:

- CPU frequency and performance management.
- CPU core and cluster configuration.
- GPU performance management.
- Advanced system-level hardware controls.
- Dual-screen management and display control.
- Integration with AYANEO system components.
- Accessibility service configuration and hardware button integration.


<img width="1024" height="768" alt="PocketHub_1794880772315_bottom 1" src="https://github.com/user-attachments/assets/15ae55b4-49d2-4858-9901-171e23217b80" />
<img width="1024" height="768" alt="PocketHub_1794880805172_bottom 1" src="https://github.com/user-attachments/assets/047244d5-dee5-4493-a476-17705cb156b8" />
<img width="1024" height="768" alt="PocketHub_1794880863067_bottom 1" src="https://github.com/user-attachments/assets/ad195495-19a6-472d-bf34-c71944260cd1" />
<img width="1024" height="768" alt="PocketHub_1794880907000_bottom 1" src="https://github.com/user-attachments/assets/b87920f4-9edc-4ea3-8e09-a2e4b450d79b" />
<img width="1024" height="768" alt="PocketHub_1794880923684_bottom 1" src="https://github.com/user-attachments/assets/c748d6f2-7d13-4ab6-a2a3-14268770476c" />
<img width="1024" height="768" alt="PocketHub_1794880944023_bottom 1" src="https://github.com/user-attachments/assets/e7230818-4f54-4c1b-b738-201000949b62" />
<img width="1024" height="768" alt="PocketHub_1794880985882_bottom 1" src="https://github.com/user-attachments/assets/19244d59-82a2-4eb9-a536-42354aa3052b" />
<img width="1024" height="768" alt="PocketHub_1794881001675_bottom 1" src="https://github.com/user-attachments/assets/e247a9ad-3a22-4be5-93ba-af7e27f219eb" />
<img width="1024" height="768" alt="PocketHub_1794881035617_bottom 1" src="https://github.com/user-attachments/assets/bf4abbe9-fd51-4896-88a2-d93c51432d3e" />
<img width="1024" height="768" alt="PocketHub_1794881043005_bottom 1" src="https://github.com/user-attachments/assets/697dfa04-7440-4550-b4c6-ba86f4569e59" />
<img width="1024" height="768" alt="PocketHub_1794881330372_bottom 1" src="https://github.com/user-attachments/assets/c1ddd52a-4b2f-4ab3-8778-3ef6a621f047" />
<img width="1024" height="768" alt="PocketHub_1794881454997_bottom 1" src="https://github.com/user-attachments/assets/f6ed311a-1fc1-4b24-bf0a-ae1e527f593f" />













---

## 🎮 Designed for AYANEO Pocket DS

The AYANEO Pocket DS is a unique Android handheld featuring two independent displays and dedicated hardware controls.

However, accessing and managing all of its hardware capabilities through the standard Android interface can be inconvenient.

Pocket Hub is designed to make these capabilities easier to access and manage.

The application focuses on:

- Simplifying access to advanced system settings.
- Centralizing hardware and performance controls.
- Improving the dual-screen experience.
- Providing more direct control over CPU and GPU behavior.
- Integrating custom controls with the device's physical buttons.
- Reducing dependence on the original AYANEO control interface.

---

## ✨ Features

### 1. Advanced Performance Management

Manage the device's performance directly from Pocket Hub.

The goal is to provide convenient access to hardware performance parameters without having to manually execute ADB commands or navigate through system configuration files.

**CPU Management**
- CPU frequency management.
- CPU performance limits.
- Individual CPU cluster configuration.
- Support for devices with multiple CPU clusters.
- CPU scheduling mode selection.
- Advanced CPU configuration.

**GPU Management**
- GPU performance monitoring and configuration.
- Access to available GPU frequency controls.
- Performance-oriented hardware settings.

These features are intended to provide greater control over the balance between performance, power consumption and temperature.

### 2. Dual-Screen Management

Take advantage of the AYANEO Pocket DS dual-display architecture.

Pocket Hub is designed to simplify control over both displays.

Features include:

- Independent upper and lower screen management.
- Ability to disable the lower display when it is not needed.
- Ability to restore the second display.
- Convenient screen switching through the device's physical button.
- Integration with the Android display system.

The objective is to make switching between single-screen and dual-screen operation as seamless as possible.

### 3. Screenshot Manager

A dedicated screenshot feature designed specifically for the dual-screen device.

The intended workflow:

1. Press the screenshot button in Pocket Hub.
2. The application captures both displays.
3. A selection window appears with previews of both screens.
4. Choose which display you want to save.
5. The selected screenshot is saved.
6. The selection window closes and a confirmation message appears.

This makes it easy to capture content from either display without manually cropping a combined screenshot.

### 4. Custom Control Interface

Pocket Hub provides a centralized interface for device management.

Instead of navigating through multiple Android settings pages, users can access supported hardware and system controls directly from the application.

The interface is designed around the unique dual-screen layout of the AYANEO Pocket DS.

### 5. Physical Hardware Button Integration

Pocket Hub aims to integrate with the device's physical hardware button.

The goal is to allow users to access their custom control center using the existing hardware button rather than relying exclusively on touchscreen navigation.

This includes managing the interaction between Pocket Hub and the original AYANEO system services.

### 6. Automatic Accessibility Configuration

Pocket Hub is designed to simplify initial setup.

During the initial setup and subsequent launches, the application checks the relevant Accessibility service configuration.

Its intended configuration is:

- Disable the original AYANEO Accessibility services: `AYAHome` and `AYAWindow`.
- Enable the Pocket Hub Accessibility service.
- Verify that the configuration has been applied.
- Recheck the configuration after device restarts and application launches.
- Restore the required configuration if the original system services have re-enabled themselves.

This helps ensure that the hardware button continues to work with Pocket Hub instead of being intercepted by the original AYANEO interface.

Other user-enabled Accessibility services should be preserved.

### 7. System-Level Integration

Pocket Hub is developed specifically for the AYANEO Pocket DS rather than being a generic Android launcher or settings application.

It is designed to interact with:

- Android system services.
- Device-specific AYANEO components.
- Hardware interfaces exposed through the Linux kernel.
- CPU and GPU system controls.
- Android display management.
- Accessibility services.

This device-specific approach makes it possible to implement functionality that would not normally be accessible through standard Android applications.

---

## 🛠 Technical Information

| Component | Details |
|---|---|
| Device | AYANEO Pocket DS |
| Operating System | Android 13 |
| Architecture | ARM64 |
| Development Language | Kotlin |
| Development Environment | Android Studio |
| System Privileges | Root required |
| Hardware Integration | AYANEO-specific |
| Interface | Custom Android UI |

---

## 🔐 Permissions

Pocket Hub requires elevated privileges for its system management functionality.

Depending on the enabled features, it may require:

- Root (`su`) access.
- Accessibility service activation.
- Permission to interact with system settings.
- Access to hardware and kernel configuration interfaces.
- Display management permissions.

Some functionality depends on the specific firmware and kernel implementation installed on the device.

---

## 🚧 Development Status

Pocket Hub is an actively developed project.

Some features are already implemented, while others are being tested and refined against the original AYANEO firmware.

The main development priorities are:

- Improving CPU and GPU performance management.
- Completing CPU cluster configuration.
- Implementing and validating CPU scheduling mode controls.
- Improving dual-screen switching reliability.
- Completing screenshot selection and saving workflows.
- Ensuring reliable hardware button integration.
- Automating Accessibility service configuration.
- Improving compatibility and stability across system reboots.

Features that depend on undocumented AYANEO system interfaces may require additional testing.

---

## 🎯 Project Goals

The long-term goal of Pocket Hub is to provide a comprehensive system control center for the AYANEO Pocket DS.

Rather than simply duplicating the original AYANEO interface, Pocket Hub aims to extend its functionality and give users more control over their hardware.

The project is focused on:

- Better hardware control.
- Easier performance configuration.
- More convenient dual-screen operation.
- Reduced reliance on external tools and ADB commands.
- A cleaner and more unified user experience.
- Greater flexibility for advanced users and enthusiasts.

---

## ⚠️ Disclaimer

Pocket Hub is an independent community development project.

It is not an official AYANEO application and is not affiliated with, endorsed by or supported by AYANEO.

The application modifies and interacts with privileged Android system components. Incorrect configuration or use may result in unexpected behavior, performance issues or system instability.

Use at your own risk.

Always ensure that you understand the implications of granting root access to applications.

---

## 🔗 Repository

**GitHub:** https://github.com/Emile86/PocketHub

Contributions, testing, bug reports and suggestions are welcome.

If you own an AYANEO Pocket DS and are interested in advanced hardware control, performance optimization and improving the dual-screen experience, feel free to follow the project.

---

**Pocket Hub — One device. Two screens. Full control.**
