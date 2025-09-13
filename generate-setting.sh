#!/bin/bash
TOKEN=$(gcloud auth print-access-token)
cat <<EOF > settings.xml
<settings>
  <servers>
    <server>
      <id>google-artifact-registry</id>
      <username>oauth2accesstoken</username>
      <password>${TOKEN}</password>
    </server>
  </servers>
</settings>
EOF
