#!/bin/bash

echo "Start update"

if [[ ${distribDirectoryUrl} == http://* ]] || [[ ${distribDirectoryUrl} == https://* ]]; then
  function downloadDesiredVersions {
    echo "Download desired versions"
    curl ${distribDirectoryUrl}/download-desired-versions --output $1 --retry 10 --retry-delay 2 --connect-timeout 5 --silent --show-error || exit 1
  }
  function downloadVersionImage {
    echo "Download version ${1} image"
    curl ${distribDirectoryUrl}/download-version/${updateService}/$1 --output $2 --retry 10 --retry-delay 2 --connect-timeout 5 --silent --show-error || exit 1
  }
elif [[ ${distribDirectoryUrl} == file://* ]]; then
  function downloadDesiredVersions {
    curl ${distribDirectoryUrl}/desired-versions.json --output $1 --silent --show-error || exit 1
  }
  function downloadVersionImage {
    curl ${distribDirectoryUrl}/services/${updateService}/${updateService}-$1.zip --output $2 --silent --show-error || exit 1
  }
else
  echo "Invalid distribution directory URL ${distribDirectoryUrl}"
  exit 1
fi

while [ 1 ]
do
  downloadDesiredVersions .desired-versions.json
  desiredVersion=`jq -r .desiredVersions.${updateService} .desired-versions.json`

  echo "Check for new version of ${updateService}"
  if [ ! -f ${updateService}-*.jar ]; then
    update="true"
  else
    currentVersion=`ls ${updateService}-*.jar | sed -e "s/^${updateService}-//; s/\.jar$//" | tail -1`
    if [ "${currentVersion}" != "${desiredVersion}" ]; then
      echo "Desired version ${desiredVersion} != current version ${currentVersion}."
      update="true"
    else
      rm -f .desired-versions.json
      update="false"
    fi
  fi

  if [ "${update}" = "true" ]; then
    echo "Update ${updateService} to version ${desiredVersion}"
    downloadVersionImage ${desiredVersion} ${updateService}.zip
    rm -f ${updateService}-*.jar || exit 1
    unzip -o ${updateService}.zip && rm -f ${updateService}.zip || exit 1
    rm -f .desired-versions.json
  fi

  buildVersion=`echo ${desiredVersion} | sed -e 's/_.*//'`

  if [ -f install.json ]; then
    command=`jq -r '.runService.command' install.json`
    args=`jq -r '.runService.args | join(" ")' install.json | sed -e s/%%version%%/${buildVersion}/`
  else
    if [ ! -f ${updateService}-${buildVersion}.jar ]; then
      echo "No <${updateService}-${buildVersion}>.jar in the build."
      exit 1
    fi
    command="/usr/bin/java"
    args="-XX:+HeapDumpOnOutOfMemoryError -XX:MaxJavaStackTraceDepth=10000000 -Dscala.control.noTraceSuppression=true -jar ${updateService}-${buildVersion}.jar"
  fi

  echo "Run ${command} ${args} $@"
  ${command} ${args} "$@"

  if [ $? -eq 9 ]; then
    echo "Service ${updateService} is obsoleted. Update it."
  else
    break
  fi
done
