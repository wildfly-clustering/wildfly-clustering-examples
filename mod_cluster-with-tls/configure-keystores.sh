#!/bin/sh
# script for re-generating the keystores and truststores needed for SSL/TLS

## Create server keystore (tlsServer.keystore, serverKeySecret)
keytool -v -genkeypair -alias localhost -keyalg RSA -keysize 2048 -keystore tlsServer.keystore -validity 365 -storepass serverKeySecret -dname "cn=localhost"

## Export server's Public Key (tlsServer.cer)
keytool -v -exportcert -alias localhost -keystore tlsServer.keystore -file tlsServer.cer -storepass serverKeySecret

## Create client keystore (tlsClient.keystore, clientKeySecret)
keytool -v -genkeypair -alias client -dname "cn=Client" -keyalg RSA -keysize 2048 -validity 365 -keystore tlsClient.keystore -storepass clientKeySecret

## Exporting client's Public Key  (tlsClient.cer)
keytool -v -exportcert -keystore tlsClient.keystore -alias client -file tlsClient.cer -storepass clientKeySecret

# Importing client's Public key into server's truststore (tlsServer.truststore, serverTrustSecret)
keytool -v -importcert -alias client -file tlsClient.cer -keystore tlsServer.truststore -storepass serverTrustSecret -trustcacerts  -noprompt

# Importing server's Public key into client's truststore (tlsClient.truststore, clientTrustSecret)
keytool -v -importcert -alias localhost -file tlsServer.cer -keystore tlsClient.truststore -storepass clientTrustSecret  -trustcacerts -noprompt
