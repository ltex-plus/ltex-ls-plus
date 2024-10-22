# Example of use:
# docker build --build-arg LTEX_VERSION=18.2.0 .
# docker run ltex-ls-plus --endless


FROM maven:3-eclipse-temurin-21-alpine AS builder
ARG LTEX_VERSION
# Argument LTEX_VERSION is mandatory
RUN test -n "$LTEX_VERSION" || (echo "Mandatory argument LTEX_VERSION not set" && false)
# Install build dependencies
RUN apk add --no-cache python3 \
    && ln -sf /usr/bin/python3 /usr/bin/python
# Download the release
RUN wget -q https://github.com/ltex-plus/ltex-ls-plus/archive/refs/tags/${LTEX_VERSION}.tar.gz \
    && tar -xzf ${LTEX_VERSION}.tar.gz \
    && rm -vf ${LTEX_VERSION}.tar.gz \
    && mv -v ltex-ls-plus-${LTEX_VERSION} ltex-ls-plus-src
WORKDIR /ltex-ls-plus-src
# Generate completion lists
RUN python -u tools/createCompletionLists.py
# Build
RUN mvn --quiet --errors package -DskipTests
# Package binary
RUN tar -xzf target/ltex-ls-plus-${LTEX_VERSION}.tar.gz \
    && mv -v ltex-ls-plus-${LTEX_VERSION} /ltex-ls-plus

FROM eclipse-temurin:21-jre-alpine
# Add a normal user to run the program
RUN adduser -D -s /sbin/nologin -h /ltex-ls-plus ltex
USER ltex
# Copy artifacts from builder
WORKDIR /ltex-ls-plus
COPY --from=builder /ltex-ls-plus/ ./
# Entrypoint
ENTRYPOINT ["/ltex-ls-plus/bin/ltex-ls-plus"]