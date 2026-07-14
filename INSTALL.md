# Building and Testing jsipdialer

This guide covers everything needed to build the project from source, including native image compilation, and run all tests.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK 21** | 21.0.x (e.g. Temurin 21) | Compile Java source code (source/target level 21) |
| **Maven** | 3.9+ (or use `./mvnw`) | Build system |
| **GraalVM CE** | 25.0.2+ | Native image compilation |
| **UPX** | 5.x | Compress the native binary |
| **Docker** | 20.10+ (API v1.44+) | Run Kamailio SIP server for integration tests |

### Optional (only needed if not using GraalVM)

- **JDK 21** can be any distribution (Temurin, Oracle, etc.), but GraalVM CE includes its own JDK and can be used as the sole Java installation.

## 1. Install JDK 21

The project requires Java 21 for compilation (`maven.compiler.source` and `maven.compiler.target` are both 21).

### Debian/Ubuntu

```bash
# Adoptium/Temurin (recommended)
curl -sL https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse \
  -o /tmp/jdk21.tar.gz
sudo tar xzf /tmp/jdk21.tar.gz -C /usr/lib/jvm/
export JAVA_HOME=/usr/lib/jvm/jdk-21.0.11+10   # adjust path to your extracted directory
export PATH="$JAVA_HOME/bin:$PATH"
```

### macOS (Homebrew)

```bash
brew install openjdk@21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### Verify

```bash
java -version    # openjdk version "21.0.x"
javac -version   # javac 21.0.x
```

## 2. Install GraalVM CE 25.0.2+

The `native-maven-plugin` (v1.1.4) and reachability metadata require GraalVM 25.x. Download the Community Edition from [GitHub Releases](https://github.com/graalvm/graalvm-ce-builds/releases/tag/jdk-25.0.2).

```bash
curl -sL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-25.0.2/graalvm-community-jdk-25.0.2_linux-x64_bin.tar.gz" \
  -o /tmp/graalvm.tar.gz
sudo tar xzf /tmp/graalvm.tar.gz -C /usr/lib/jvm/
export GRAALVM_HOME=/usr/lib/jvm/graalvm-community-openjdk-25.0.2+10.1  # adjust path
```

> **Note:** macOS users should download the `-macos-x64_bin.tar.gz` or `-macos-aarch64_bin.tar.gz` variant.

### Verify

```bash
$GRAALVM_HOME/bin/native-image --version
# native-image 25.0.2 ...
```

## 3. Install UPX

UPX compresses the native binary from ~17 MB to ~5 MB. Download from [GitHub Releases](https://github.com/upx/upx/releases/tag/v5.2.0).

```bash
curl -sL "https://github.com/upx/upx/releases/download/v5.2.0/upx-5.2.0-amd64_linux.tar.xz" \
  -o /tmp/upx.tar.xz
tar xJf /tmp/upx.tar.xz -C /tmp/
sudo cp /tmp/upx-5.2.0-amd64_linux/upx /usr/local/bin/upx
```

### Verify

```bash
upx --version
# upx 5.2.0
```

## 4. Install Docker

Docker is required for integration tests. Testcontainers builds and runs a Kamailio SIP server container.

### Debian/Ubuntu

```bash
sudo apt-get install -y docker.io
sudo systemctl start docker
sudo usermod -aG docker $USER    # log out and back in
```

### Verify

```bash
docker ps
```

## 5. Configure Maven Repository Access (if needed)

The project depends on `org.mjsip:mjsip-sip:2.0.5` and `org.mjsip:mjsip-server:2.0.5` from the [haumacher/mjSIP](https://github.com/haumacher/mjSIP) GitHub Packages repository.

If the artifacts are not in your local Maven cache (`~/.m2/repository/org/mjsip/`), you need a GitHub Personal Access Token (PAT) with `read:packages` scope:

```bash
mkdir -p ~/.m2
cat > ~/.m2/settings.xml << 'EOF'
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PAT</password>
    </server>
  </servers>
</settings>
EOF
```

## 6. Build

Set environment variables and run the full build:

```bash
export JAVA_HOME=/usr/lib/jvm/jdk-21.0.11+10          # your JDK 21 path
export GRAALVM_HOME=/usr/lib/jvm/graalvm-community-openjdk-25.0.2+10.1  # your GraalVM path
export PATH="$JAVA_HOME/bin:$GRAALVM_HOME/bin:$PATH"
```

### Compile and run unit tests only

```bash
mvn test
```

### Full build (compile, test, package, native image, integration tests)

```bash
mvn clean verify
```

This will:
1. Compile source and test code
2. Run unit tests (6 tests in `SipClientMainTest`)
3. Package the fat JAR (`target/jsipdialer-0.0.4-SNAPSHOT.jar`)
4. Build the GraalVM native image (`target/jsipdialer`)
5. Compress with UPX
6. Run integration tests (11 tests in `SipRegistrarIT`, 9 tests in `SipClientMainNativeIT`)
7. Verify all tests pass

### Skip specific phases

```bash
# Skip native image build
mvn clean verify -Dnative.skip=true

# Skip integration tests
mvn clean verify -DskipTests=false -DskipITs=true

# Skip UPX compression
mvn clean verify -Dexec.skip=true
```

## 7. Run the Application

### Using the native binary

```bash
SIP_USERNAME='user' SIP_PASSWORD='pass' ./target/jsipdialer \
  -sipServerAddress 'sip.example.com' \
  -destinationNumber '**9' \
  -timeout 20
```

### Using the JAR

```bash
java -jar target/jsipdialer-0.0.4-SNAPSHOT.jar \
  -sipServerAddress 'sip.example.com' \
  -destinationNumber '**9'
```

## Test Summary

When everything is set up correctly, `mvn clean verify` produces:

```
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0   (unit tests)
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0   (integration tests)
BUILD SUCCESS
```

## Troubleshooting

### GraalVM version mismatch

If you see `reachability-metadata schema` errors, ensure you are using GraalVM CE **25.0.2+**, not an older JDK 21 or JDK 17 variant.

### Docker API version too old

If Testcontainers fails with `client version X is too old`, set `DOCKER_API_VERSION=1.44` before running Maven, or upgrade Docker.

### Missing mjSIP artifacts

If `mvn` fails with `Could not find artifact org.mjsip:mjsip-sip:2.0.5`, configure the GitHub Packages repository as described in step 5.

### Native binary not found for integration tests

The `SipClientMainNativeIT` integration tests are guarded by `@EnabledIf("nativeBinaryExecutable")` and are automatically skipped if `target/jsipdialer` does not exist or is not executable.
