# Releasing

Releases go to Maven Central through the Central Portal.

## Once

- An account on [central.sonatype.com](https://central.sonatype.com) with the
  namespace `io.github.mishastoyanov` verified, and a user token from it in
  `~/.m2/settings.xml`:

  ```xml
  <servers>
    <server>
      <id>central</id>
      <username>token user</username>
      <password>token password</password>
    </server>
  </servers>
  ```

- A GPG key whose public half is on a key server
  (`gpg --keyserver keyserver.ubuntu.com --send-keys <id>`).

## Each release

1. Make sure `master` is green in CI.
2. In `CHANGELOG.md`, rename `Unreleased` to the version and date, and start
   a new empty `Unreleased` above it. Update the status line in `README.md`.
3. Set the version: `mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false`,
   and the same in `samples/quickstart/pom.xml` (`llm-profiles.version`).
4. Commit, tag `vX.Y.Z`.
5. `mvn -Prelease clean deploy`. It builds, tests, signs and uploads, then
   stops: the deployment waits in the Central Portal, where it is checked and
   published by hand. A published version cannot be withdrawn.
6. Set the next snapshot (`X.Y+1.0-SNAPSHOT`), commit, push the commits and
   the tag.

Before the first release the project still needs its final name; see the open
question in `README.md`.
