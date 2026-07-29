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

Under `ui.apps` edit pom.xml dependencies. 

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
same". Run:

```bash
JAVA_HOME=/path/to/jdk11 tools/verify.sh
JAVA_HOME=/path/to/jdk21 tools/verify.sh
```

Both must pass before merging. `tools/verify.sh` builds the reactor, diffs a
normalized manifest against `tools/golden/manifest.fingerprint`, and runs
`tools/smoke/JerseyBundleSmoke.java` against the jars actually embedded in the
built bundle.

If a change intentionally alters the bundle's OSGi contract, regenerate the
golden with `UPDATE_GOLDEN=1 tools/verify.sh` and include the diff in your PR
description — a golden change is a consumer-visible change and needs review.
