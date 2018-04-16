# fop-custom
Library with customizations & fixes to Apache FOP 2.1

It includes fixes for:
  * https://issues.apache.org/jira/browse/FOP-2536
  * https://issues.apache.org/jira/browse/FOP-2549

## Building
  * mvn package

## Deployment

As it's a patch jar, it should be placed in the classpath before the main FOP jar.
