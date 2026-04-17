
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
	@echo "  make [all|build]   - Build common, enclave (native), and host artifacts"
	@echo "  make common        - Build the shared Java library"
	@echo "  make enclave       - Build enclave artifacts (default profile: $(ENCLAVE_PROFILE))"
	@echo "  make host          - Build the test package (depends on enclave output)"
	@echo "  make test          - Run JUnit test suite (requires sudo for subprocesses)"
	@echo "  make clean         - Run 'mvn clean' for the whole aggregate project"
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

# Run JUnit test suite via shaded jar + test-scoped classpath (includes console-standalone).
test:
	@TEST_CP=$$($(MVN) -f crashme/host/pom.xml -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null); \
	sudo java -cp "crashme/host/target/crashme-crashme.jar:crashme/host/target/test-classes:$$TEST_CP" \
		org.junit.platform.console.ConsoleLauncher execute \
		--select-class=ch.usi.inf.confidentialstorm.CrashMe \
		--disable-banner  \
		--details=testfeed
