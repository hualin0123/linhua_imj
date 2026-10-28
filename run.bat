@echo off
setlocal

rem Build the project and run the main class with Maven-managed dependencies
mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.linhua.helloworld.HelloWorld_
