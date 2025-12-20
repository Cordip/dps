## J1

./gradlew installDist

./app/build/install/app/bin/app -m server -p 9090 -t 4 -k 2048

./app/build/install/app/bin/app -m client -p 9090 -n Alice

./app/build/install/app/bin/app -m client -p 9090 -n Bob -d 5

./app/build/install/app/bin/app -m client -p 9090 -n Charlie -c


## J2

### main

./gradlew :app:run --console=plain

### analysis

./gradlew benchmark --console=plain

## J3

java -jar Task_J3-server.jar MaxMax

./gradlew run
