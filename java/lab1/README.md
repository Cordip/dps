## to run

./gradlew installDist

./app/build/install/app/bin/app -m server -p 9090 -t 4 -k 2048

./app/build/install/app/bin/app -m client -p 9090 -n Alice

./app/build/install/app/bin/app -m client -p 9090 -n Bob -d 5

./app/build/install/app/bin/app -m client -p 9090 -n Charlie -c
