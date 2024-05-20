# jsipdialer

[![GraalVM Native Image builds](https://github.com/pfichtner/jsipdialer/actions/workflows/maven.yml/badge.svg)](https://github.com/pfichtner/jsipdialer/actions/workflows/maven.yml)

SIP-Dialer implemented in Java. 

I needed something to initates a phone call on my Fritz!Box [TM] without having TR-064 enabled. 
The call should be initialized using a soft phone (SIP). https://github.com/tmakkonen/sipcmd and https://github.com/guisousanunes/sipcmd2 would do so but unfortunately they not compile on todays distros since missing/outdated dependencies. So I did write my own. 

```SIP_USERNAME='theSipUser' SIP_PASSWORD='theSipUsersPassword' ./jsipdialer -sipServerAddress 'sipservername.local' -destinationNumber '**9' -callerName 'The caller's name to display' -timeout 20```

Calls the numnber ```**9``` ("Sammelrufnummer" on Fritz!Boxes [TM]) for at most 20 seconds. 
USERNAME and PASSWORD are passed via environment variables for security reasons (you should not pass confidential information via command line arguments)
```callerName``` and ```timeout``` are optional. 

You can download native-images ("executables") from the [releases](https://github.com/pfichtner/jsipdialer/releases). 
At the moment I do only build native-images for linux-x86, linux-arm64 (e.h. Raspberry PI) runners are currently not supported by github itself and would need a self-hosted-runner. 
Of course you can use jsipdialer without a native-image but then you need a JRE 17 or higher installed. Instead of calling the binary you would call then ```java -jar jsipdialer.jar```


