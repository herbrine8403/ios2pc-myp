.PHONY: help build test clean package

help:
	@echo "Targets: build test clean package"

build:
	./gradlew build

test:
	./gradlew test

clean:
	./gradlew clean

package:
	./gradlew jar
