#!/bin/bash
export CONTAINER_NAME=recroom

SERVER_PATH=/opt/ol/wlp/usr/servers/defaultServer
ssl_path=${SERVER_PATH}/resources/security

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

  etcdctl get /proxy/third-party-ssl-cert > ${ssl_path}/cert.pem

  export RECROOM_SERVICE_URL=$(etcdctl get /room/service)
  export MAP_SERVICE_URL=$(etcdctl get /room/mapurl)
  export MAP_HEALTH_SERVICE_URL=$(etcdctl get /room/maphealth)
  export MAP_KEY=$(etcdctl get /passwords/map-key)
  export SYSTEM_ID=$(etcdctl get /global/system_id)

  GAMEON_MODE=$(etcdctl get /global/mode)
  export GAMEON_MODE=${GAMEON_MODE:-production}
  export TARGET_PLATFORM=$(etcdctl get /global/targetPlatform)

  export KAFKA_SERVICE_URL=$(etcdctl get /kafka/url)
fi
if [ -f /etc/cert/cert.pem ]; then
  cp /etc/cert/cert.pem ${ssl_path}/cert.pem
fi


if [ -f ${ssl_path}/cert.pem ] ; then
  echo "Building keystore/truststore from cert.pem"
  echo "-creating dir"
  echo "-cd dir"
  cd ${ssl_path}
  echo "-converting pem to pkcs12"
  openssl pkcs12 -passin pass:keystore -passout pass:keystore -export -out cert.pkcs12 -in cert.pem
  echo "-importing pem to truststore.jks"
  keytool -import -v -trustcacerts -alias default -file cert.pem -storepass truststore -keypass keystore -noprompt -keystore truststore.jks
  echo "-creating dummy key.jks"
  keytool -genkey -storepass testOnlyKeystore -keypass wefwef -keyalg RSA -alias endeca \
          -keystore key.jks -dname CN=rsssl,OU=unknown,O=unknown,L=unknown,ST=unknown,C=CA
  echo "-emptying key.jks"
  keytool -delete -storepass testOnlyKeystore -alias endeca -keystore key.jks
  echo "-importing pkcs12 to key.jks"
  keytool -v -importkeystore -srcalias 1 -alias 1 -destalias default -noprompt \
          -srcstorepass keystore -deststorepass testOnlyKeystore -srckeypass keystore -destkeypass testOnlyKeystore \
          -srckeystore cert.pkcs12 -srcstoretype PKCS12 -destkeystore key.jks -deststoretype JKS
  echo "done"
  cd ${SERVER_PATH}
fi

exec /opt/ol/wlp/bin/server run defaultServer
