TODO:

- [ ] appengine-web.xml auslesen um default project herauszufinden
      somit kann man auch ein error werfen, wenn es mehr als 2 default projecte geben würde
- [ ] SBT Plugins
- [ ] HttpServletResponse in HttpServletResponseWrapper wrappen für bessere Methoden
- [ ] Support for WebSockets

Development:

sbt publishLocal && (cd src/play-appengine-plugin/src/sbt-test/appengine; sbt -Dplugin.version=0.1.0-SNAPSHOT runAll; cd -)

Deployment:

sbt publishLocal && (cd src/play-appengine-plugin/src/sbt-test/appengine; sbt -Dplugin.version=0.1.0-SNAPSHOT deployApp; cd -)


TODO AE:
https://github.com/GoogleCloudPlatform/app-gradle-plugin/issues/88
https://cloud.google.com/appengine/docs/standard/java/config/cron#creating_a_cron_job

Exploded War:
:point_up: [23. November 2017 12:58](https://gitter.im/sbt/sbt?at=5a16b7f6982ea2653fb62a7d)