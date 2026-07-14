# jsipdialer

[![GraalVM Native Image builds](https://github.com/pfichtner/jsipdialer/actions/workflows/maven.yml/badge.svg)](https://github.com/pfichtner/jsipdialer/actions/workflows/maven.yml)

SIP dialer implemented in Java.

I needed something to initiate a phone call on my FRITZ!Box without having TR-064 enabled. The call should be initialized using a softphone (SIP). Projects such as https://github.com/tmakkonen/sipcmd and https://github.com/guisousanunes/sipcmd2 would do that, but unfortunately they do not compile on today's Linux distributions because of missing or outdated dependencies. So I wrote my own.

```sh
SIP_USERNAME='theSipUser' SIP_PASSWORD='theSipUsersPassword' ./jsipdialer \
  -sipServerAddress 'sipservername.local' \
  -destinationNumber '**9' \
  -callerName "The caller's name to display" \
  -timeout 20
```

Calls the number `**9` ("Sammelrufnummer" on FRITZ!Box devices) for at most 20 seconds.

`SIP_USERNAME` and `SIP_PASSWORD` are passed via environment variables for security reasons. Confidential information should generally not be passed via command-line arguments because it may be visible to other users or processes on the system.

`callerName` and `timeout` are optional.

You can download native images (executables) from the [releases](https://github.com/pfichtner/jsipdialer/releases).

At the moment, native images are built for Linux x86-64 only. Linux ARM64 builds (for example, for Raspberry Pi systems) are currently not available because the required runners are not provided by GitHub and would require a self-hosted runner.

Of course, you can also use jsipdialer without a native image. In that case, you need a JRE 21 or newer installed and can run:

```sh
java -jar jsipdialer.jar
```

## Trademark Notice

FRITZ! and FRITZ!Box are trademarks of FRITZ! GmbH.

This project is an independent, unofficial tool and is not affiliated with, endorsed by, or sponsored by FRITZ! GmbH.
