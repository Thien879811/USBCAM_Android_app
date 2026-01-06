# USBCam

A generic Android USB Camera application built using `libuvc` and `libausbc`.

## Prerequisites

- **Android Studio** Ladybug or newer (recommended).
- **Android SDK** (Target SDK 36).
- **NDK** (Side-by-side) - The project is configured to use NDK version `27.0.12077973` or similar. CMake 3.22.1+.

## Setup & Configuration

1.  **Clone the repository**:
    ```bash
    git clone https://github.com/your-repo/usbcam.git
    ```

2.  **Open in Android Studio**:
    Open the `usbcam` directory as an existing Android Studio project.

3.  **Configure Android SDK Path (`local.properties`)**:
    The build system requires the location of your Android SDK. This is defined in the `local.properties` file in the root directory.

    If this file does not exist, create it. Add the `sdk.dir` property pointing to your SDK location.

    **Windows Example:**
    Note the use of `\\` or `/` for path separators.
    ```properties
    ## This file must *NOT* be checked into Version Control Systems,
    # as it contains information specific to your local configuration.
    
    # Location of the SDK. This is only used by Gradle.
    # Replace USER_NAME with your actual Windows username.
    sdk.dir=C\:\\Users\\USER_NAME\\AppData\\Local\\Android\\Sdk
    ```
    
    *If your SDK is at `C:\Users\LVL_TX2\AppData\Local\Android\Sdk`, the line should look like:*
    ```properties
    sdk.dir=C\:\\Users\\LVL_TX2\\AppData\\Local\\Android\\Sdk
    ```

    **macOS / Linux Example:**
    ```properties
    sdk.dir=/Users/username/Library/Android/sdk
    ```

4.  **NDK Configuration**:
    The project attempts to locate the NDK automatically. If you encounter NDK issues, ensure you have the NDK installed via the SDK Manager.
    
    You can also explicitly set the NDK path in `local.properties` if needed:
    ```properties
    ndk.dir=C\:\\Users\\USER_NAME\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973
    ```

## Build & Run

1.  Sync the project with Gradle files.
2.  Connect a USB Camera to your Android device (via OTG adapter if necessary).
3.  Run the `app` configuration.
4.  Grant camera permissions when prompted.

## Features

- Supports UVC cameras (Webcams, etc.).
- Preview and capture functionality.
- Custom `libuvc` and `libausbc` local modules for full control.
