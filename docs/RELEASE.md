# Release process

1. **Verify** — run the full build with signing locally:

   ```bash
   mvn -B clean verify
   ```

   This runs tests, generates `sources`/`javadoc` jars, and GPG-signs every
   artifact (requires a configured signing key in `~/.m2/settings.xml`).

2. **Changelog** — move the `Unreleased` section into a dated `## <version>`
   section and summarize the changes.

3. **Commit and tag**:

   ```bash
   git commit -m "release: <version>"
   git tag -a <version> -m "Freeway Ext <version>"
   git push origin main --tags
   ```

4. **Deploy** to Maven Central (requires `central` server credentials in
   `~/.m2/settings.xml`):

   ```bash
   mvn -B deploy
   ```

5. **Benchmark archive** — when the release includes performance claims, run
   the suite with the report written into the repo:

   ```bash
   mvn -f freeway-benchmark/pom.xml exec:java \
     -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
     -Dexec.args="suite --engines=... --output=docs/benchmark-<version>.md"
   ```

6. Verify the published artifacts on Maven Central before announcing.
