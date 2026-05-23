PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
PRG_DIR="$(cd -P "$(dirname "$PRG")" && pwd)"

JAR_PATTERN="myweb-scaffold-*.jar"
jar_file=$(ls $PRG_DIR/target/$JAR_PATTERN 2>/dev/null | tail -n 1)
if [ -z "$jar_file" ]; then
    echo "Error: jar file: '$PRG_DIR/target/$JAR_PATTERN' not found."
    exit 1
fi

echo ">> Run with jar file: $jar_file"
java -jar $jar_file --spring.config.additional-location="optional:file:${PRG_DIR}/src/main/resources/application.properties"
