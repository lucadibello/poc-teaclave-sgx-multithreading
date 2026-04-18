
WORKDIR := crashme
ROOT_POM := $(WORKDIR)/pom.xml
RUNNER_SCRIPT := $(WORKDIR)/run-local.sh

MVN ?= mvn
MAVEN_GOALS ?= package
MAVEN_FLAGS ?= -DskipTests
# Toggle native profile (requires SGX-enabled env) via `make ENCLAVE_PROFILE= enclave`.
ENCLAVE_PROFILE ?= -Pnative

.PHONY: all build help clean common enclave host submit run-local test

all: build

help:
	@echo "Make targets:"
	@echo "  make [all|build]   - Orchestrates the full build process: common library, native enclave, and host application."
	@echo "  make common        - Compiles the shared Java API and data models used by both host and enclave."
	@echo "  make enclave       - Builds the native SGX enclave using GraalVM Native Image and signs the resulting binary."
	@echo "  make host          - Compiles the host-side application, bundling the signed enclave binary into resources."
	@echo "  make test          - Runs the JUnit test suite via Maven Surefire (redirects output, use for CI/automated checks)."
	@echo "  make test-console  - Runs JUnit tests via ConsoleLauncher to bypass Surefire and see raw enclave/native logs."
	@echo "  make test-method   - Runs a specific test method. Usage: make test-method METHOD=methodName"
	@echo "  make run-local     - Executes the host application (requires sudo) for manual triage of enclave crashes."
	@echo "  make clean         - Removes all build artifacts, target directories, and temporary native-image files."
	@echo ""
	@echo "Variables:"
	@echo "  MVN=<path>                Override Maven binary (default: mvn)"
	@echo "  MAVEN_FLAGS='<flags>'     Extra flags (default: $(MAVEN_FLAGS))"
	@echo "  ENCLAVE_PROFILE='<flags>' Profile(s) to pass while building enclave"

build: common enclave host

build-streamlined:
	$(MVN) -f $(ROOT_POM) $(MAVEN_FLAGS) $(ENCLAVE_PROFILE) clean $(MAVEN_GOALS)

clean:
	$(MVN) -f $(ROOT_POM) clean

common:
	$(MVN) -f $(ROOT_POM) -pl common -am $(MAVEN_FLAGS) $(MAVEN_GOALS)

enclave:
	$(MVN) -f $(ROOT_POM) -pl enclave -am $(MAVEN_FLAGS) $(ENCLAVE_PROFILE) $(MAVEN_GOALS)

host:
	$(MVN) -f $(ROOT_POM) -pl host -am $(MAVEN_FLAGS) $(MAVEN_GOALS)

run-local:
	@echo "Running crash triage..."
	@sudo java -jar crashme/host/target/crashme-crashme.jar
	@echo "Finished local run."

test:
	sudo $(MVN) -f $(ROOT_POM) test -pl host -am

test-debug:
	@TEST_CP=$$($(MVN) -f crashme/host/pom.xml -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null); \
	sudo java -cp "crashme/host/target/crashme-crashme.jar:crashme/host/target/test-classes:$$TEST_CP" \
		org.junit.platform.console.ConsoleLauncher execute \
		--select-class=ch.usi.inf.confidentialstorm.CrashMe \
		--disable-banner  \
		--details=testfeed

test-method:
	sudo $(MVN) -f $(ROOT_POM) test -pl host -am -Dtest=ch.usi.inf.confidentialstorm.CrashMe#$(METHOD)

test-method-debug:
	@TEST_CP=$$($(MVN) -f crashme/host/pom.xml -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null); \
	sudo java -cp "crashme/host/target/crashme-crashme.jar:crashme/host/target/test-classes:$$TEST_CP" \
		org.junit.platform.console.ConsoleLauncher execute \
		--select-method=ch.usi.inf.confidentialstorm.CrashMe#$(METHOD) \
		--disable-banner  \
		--details=testfeed
