# Gemini Eclipse Companion Plugin

This Eclipse plugin acts as a companion server for the [Gemini CLI](https://geminicli.com/), enabling IDE-specific features like native diffing and context awareness directly within Eclipse and compatible IDEs (like Spring Tool Suite).

It implements the [Gemini CLI Companion Plugin Interface Specification](https://geminicli.com/docs/ide-integration/ide-companion-spec/) by running a local HTTP server that the CLI can connect to.

## Features

*   **IDE Context Awareness:** Automatically sends information about the current workspace, open files, active editor, and selected text to the Gemini CLI.
*   **Native Diffing:** Implements the `openDiff` tool, allowing the Gemini CLI to open a native Eclipse compare view to show proposed code changes.
*   **Terminal Integration:** Creates a shell script that can be sourced by your terminal to automatically configure the necessary environment variables, ensuring the CLI connects to the correct IDE instance.

## Prerequisites

*   An Eclipse-based IDE, such as **Eclipse IDE for RCP and RAP Developers** or **Spring Tool Suite (STS)**.
*   A **Java 17 JDK/JRE** configured to run the IDE.

## Building the Plugin

To build the plugin, you need to export it as a deployable JAR file from your Eclipse workspace.

1.  In the "Package Explorer", right-click on the `navicon.gemini.eclipse.companion` project.
2.  Select **Export...**.
3.  In the wizard, expand **Plug-in Development** and select **Deployable plug-ins and fragments**. Click **Next**.
4.  Ensure the `navicon.gemini.eclipse.companion` plugin is checked.
5.  Under **Destination**, select **Directory** and browse to an empty folder where you want to save the output.
6.  Click **Finish**.

Eclipse will export the plugin JAR file into a `plugins/` sub-folder within your chosen destination directory. The file will be named something like `navicon.gemini.eclipse.companion_1.0.0.qualifier.jar`.

## Installation

1.  Locate the installation directory of your target Eclipse or STS IDE.
2.  Find the `dropins/` folder inside the installation directory.
3.  Copy the exported JAR file (e.g., `navicon.gemini.eclipse.companion_1.0.0.qualifier.jar`) into the `dropins/` folder.
4.  Restart the Eclipse/STS IDE. For the first installation, it is highly recommended to restart from the command line with the `-clean` flag to ensure the plugin cache is cleared.
    ```bash
    # On Linux/macOS
    ./eclipse -clean
    
    # On Windows
    eclipse.exe -clean
    ```

## Terminal Integration (Bash Setup)

To allow the Gemini CLI to automatically discover and connect to the running Eclipse instance, you need to configure your shell to source an environment file that the plugin creates.

1.  **How it Works:** When the plugin starts, it creates a file at `/tmp/gemini/eclipse_env.sh`. This file contains the `GEMINI_CLI_IDE_SERVER_PORT` and `GEMINI_CLI_IDE_WORKSPACE_PATH` environment variables.

2.  **Configure your `~/.bashrc`:** Open your `~/.bashrc` file (or `~/.zshrc` if you use Zsh) in a text editor and add the following lines to the end of the file:

    ```bash
    # Load Gemini Eclipse companion environment variables if available
    if [ -f /tmp/gemini/eclipse_env.sh ]; then
      source /tmp/gemini/eclipse_env.sh
    fi
    ```

3.  **Apply the Changes:** Save the file and open a new terminal. Any new terminal session will now automatically have the correct environment variables set whenever the Eclipse plugin is running.

## Usage

Once installed, the plugin runs automatically in the background. There is no UI.

You can interact with it via the Gemini CLI from a configured terminal. The CLI will detect the environment variables and connect to the plugin to provide an integrated experience.
