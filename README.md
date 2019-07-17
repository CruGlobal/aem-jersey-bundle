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
