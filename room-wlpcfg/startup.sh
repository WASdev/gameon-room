#!/bin/bash

# Configure our link to etcd based on shared volume with secret
if [ ! -z "$ETCD_SECRET" ]; then
  . /data/primordial/setup.etcd.sh /data/primordial $ETCD_SECRET
fi

export CONTAINER_NAME=recroom

SERVER_PATH=/opt/ibm/wlp/usr/servers/defaultServer

if [ "$ETCDCTL_ENDPOINT" != "" ]; then
  echo Setting up etcd...
  echo "** Testing etcd is accessible"
  etcdctl --debug ls
  RC=$?

  while [ $RC -ne 0 ]; do
      sleep 15

      # recheck condition
      echo "** Re-testing etcd connection"
      etcdctl --debug ls
      RC=$?
  done
  echo "etcdctl returned sucessfully, continuing"

  mkdir -p ${SERVER_PATH}/resources/security
  cd ${SERVER_PATH}/resources/
  etcdctl get /proxy/third-party-ssl-cert > cert.pem
  openssl pkcs12 -passin pass:keystore -passout pass:keystore -export -out cert.pkcs12 -in cert.pem
  keytool -import -v -trustcacerts -alias default -file cert.pem -storepass truststore -keypass keystore -noprompt -keystore security/truststore.jks
  keytool -genkey -storepass testOnlyKeystore -keypass wefwef -keyalg RSA -alias endeca -keystore security/key.jks -dname CN=rsssl,OU=unknown,O=unknown,L=unknown,ST=unknown,C=CA
  keytool -delete -storepass testOnlyKeystore -alias endeca -keystore security/key.jks
  keytool -v -importkeystore -srcalias 1 -alias 1 -destalias default -noprompt -srcstorepass keystore -deststorepass testOnlyKeystore -srckeypass keystore -destkeypass testOnlyKeystore -srckeystore cert.pkcs12 -srcstoretype PKCS12 -destkeystore security/key.jks -deststoretype JKS
  cd ${SERVER_PATH}

  export RECROOM_SERVICE_URL=$(etcdctl get /room/service)
  export MAP_SERVICE_URL=$(etcdctl get /room/mapurl)
  export MAP_HEALTH_SERVICE_URL=$(etcdctl get /room/maphealth)
  export MAP_KEY=$(etcdctl get /passwords/map-key)
  export SYSTEM_ID=$(etcdctl get /global/system_id)

  GAMEON_MODE=$(etcdctl get /global/mode)
  export GAMEON_MODE=${GAMEON_MODE:-production}
  export TARGET_PLATFORM=$(etcdctl get /global/targetPlatform)

  export KAFKA_SERVICE_URL=$(etcdctl get /kafka/url)

  #to run with message hub, we need a jaas jar we can only obtain
  #from github, and have to use an extra config snippet to enable it.
  export MESSAGEHUB_USER=$(etcdctl get /kafka/user)
  export MESSAGEHUB_PASSWORD=$(etcdctl get /passwords/kafka)
  wget https://github.com/ibm-messaging/message-hub-samples/raw/master/java/message-hub-liberty-sample/lib-message-hub/messagehub.login-1.0.0.jar
fi

exec /opt/ibm/wlp/bin/server run defaultServer
