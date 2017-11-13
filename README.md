

TODO:

- [ ] SBT Plugins
- [ ] HttpServletResponse in HttpServletResponseWrapper wrappen für bessere Methoden
- [ ] Support for WebSockets

Development:

sbt publishLocal && (cd src/play-appengine-plugin/src/sbt-test/appengine; sbt -Dplugin.version=0.1.0-SNAPSHOT runApp; cd -)

Deployment:

sbt publishLocal && (cd src/play-appengine-plugin/src/sbt-test/appengine; sbt -Dplugin.version=0.1.0-SNAPSHOT deployApp; cd -)
