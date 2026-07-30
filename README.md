# Jersey Bundle AEM project

This is a project is use to manage the Jersey Client files and dependencies.

## Modules
```
org.glassfish.jersey.core:jersey-client - 2.25.1
javax.annotation:javax.annotation-api - 1.3.2
```

## Transitive Dependencies
```
org.glassfish.hk2:hk2-utils - 2.5.0
org.glassfish.hk2:hk2-api - 2.5.0
org.glassfish.jersey.bundles.repackaged:jersey-guava - 2.25.1
javax.ws.rs:javax.ws.rs-api - 2.1   
```

## Update version

Dependency versions are managed in the root `pom.xml` — the `jersey.version` and
`hk2.version` properties, and the `<dependencyManagement>` block below them.
(`ui.apps/pom.xml` declares no dependency versions of its own.)

Any dependency change alters what is embedded in the bundle, so it will change
the golden fingerprint — see *Verifying a change*.

## How to build

To build all the modules run in the project root directory the following command with Maven 3:

    mvn clean install

If you have a running AEM instance you can build and package the whole project and deploy into AEM with  

    mvn clean install -PautoInstallPackage

Or to deploy it to a publish instance, run

    mvn clean install -PautoInstallPackagePublish

Or alternatively

    mvn clean install -PautoInstallPackage -Daem.port=4503

## Supported JDKs

| JDK | Build | Runtime |
|-----|-------|---------|
| 11  | supported (current AEM build JDK) | supported |
| 17  | supported | supported |
| 21  | supported | supported |

The bundle declares `Require-Capability: osgi.ee JavaSE 1.8`, which is a floor,
not a pin — the same artifact resolves on a Java 11 and a Java 21 OSGi framework.
Do not raise it.

## Verifying a change

This repo has no Java sources; it repackages Jersey into a single OSGi bundle.
Correctness therefore means "the manifest and embedded jars still behave the
same". Run it once per JDK:

```bash
JAVA_HOME=/path/to/jdk11 tools/verify.sh
JAVA_HOME=/path/to/jdk21 tools/verify.sh
```

Both must pass before merging. `tools/verify.sh` builds the reactor and then:

1. diffs a normalised manifest against `tools/golden/manifest.fingerprint`,
   including a SHA-256 of every embedded jar;
2. runs `tools/smoke/JerseyBundleSmoke.java` against the jars actually embedded
   in the built bundle, on a classpath assembled in `Bundle-ClassPath` order;
3. asserts `ui.apps`' content package still embeds the bundle under
   `/apps/jersey-bundle/install` — that package is what reaches AEM.

The smoke run prints a JUL `WARNING` on stderr about HK2 failing to reify
`DataSourceProvider` (`NoClassDefFoundError: javax/activation/DataSource`).
That is expected: `javax.activation` is an OSGi `Import-Package` rather than an
embedded jar, and it was removed from the JDK in 11, so it is absent from the
harness' flat classpath but present in AEM. The exit code and the
`SMOKE PASS`/`SMOKE FAIL` line are authoritative.

If a change intentionally alters the bundle's OSGi contract, regenerate the
golden with `UPDATE_GOLDEN=1 tools/verify.sh` and include the diff in your PR
description — a golden change is a consumer-visible change and needs review.
The golden is only rewritten after every check passes, so a failed run can
never leave it truncated.

Note that `Bundle-ClassPath` entries are recorded in order, not sorted: the
bundle embeds two jars carrying `javax.inject.*` and two carrying
`javax.annotation.*`, and OSGi resolves duplicates first-entry-wins, so a
reordering is a real change. `Bundle-Version` is excluded and the project
version is normalised to `@PROJECT_VERSION@`, so a routine release bump does
not read as a contract change.
