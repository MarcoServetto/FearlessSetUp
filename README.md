# Fearless setup

The setup program prepares everything needed to work on Fearless:
the JDK, the Eclipse IDE for Java Developers, an Eclipse workspace and the Fearless repositories.
Everything goes into one folder, the setup folder, chosen when the setup program starts.
Deleting the setup folder removes everything the setup program did.

## Download and start

Download the archive for your system from the `setup` release of this repository,
https://github.com/MarcoServetto/FearlessSetUp/releases/tag/setup:
`fearless_setup_linux.tar.gz` or `fearless_setup_windows.zip`.
No Java installation is needed: the setup program carries its own Java runtime.

Linux: extract the archive, for example with `tar xzf fearless_setup_linux.tar.gz`,
then start `fearless_setup/bin/fearless_setup`.

Windows: right click the zip file, choose "Extract All...", then open the extracted
`fearless_setup_windows` folder, then its `fearless_setup` folder, and double click `fearless_setup.exe`.
The setup program is not signed, so Windows may show "Windows protected your PC":
press "More info", then "Run anyway".

## What the setup program asks

First the setup folder: a new folder, or the folder of an earlier setup.
On the folder of an earlier setup, the setup program completes what is missing and starts Eclipse.

Then where the Fearless repositories come from:
- Official: the setup program downloads the official repositories.
  You can build, run and change the code, but you can not push your changes to GitHub.
- Existing folder: a folder that already holds the seven repositories
  (Commons, Frontend, Coordinator, StandardLibrary, Controllers, FearlessTour, ZeroToHero), official or forks.
- My forks: the setup program guides you to fork the seven repositories on GitHub and to clone your forks
  with GitHub Desktop (Windows) or git and the GitHub command line tool gh (Linux),
  so that you can push your changes and open pull requests.

Then the setup program downloads the JDK and Eclipse, prepares the workspace and starts Eclipse.
To start Eclipse later, start the setup program again and choose the same setup folder.

## In Eclipse

Every project compiles with no errors.
The Run menu runs the Java scripts of `Coordinator/Build/src/scripts`, for example `TestAllFrontend`.
A JUnit test runs with a right click on it, then Run As, JUnit Test.
Every launch runs with assertions enabled.
