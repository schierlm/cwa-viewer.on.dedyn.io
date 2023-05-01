#!/bin/sh -e

if [ -z "$1" ]; then
	#curl -s -S 'https://svc90.main.px.t-online.de/version/v1/diagnosis-keys/country/EUR/date' | jq -r '.[]|["./download.sh",.]|@sh' | sh -e
	echo '{' >list.json
	for i in *.zip; do
		if [ "$i" == "${i%@*}" ]; then
			dt=${i%.zip}
			op='true'
			for j in $dt@?.zip $dt@??.zip; do
				pfx=${j#*@}
				if [[ "$pfx" != "?"* ]] ; then
					op=$op,${pfx%.zip}
				fi
			done
			echo '"'$dt'":['$op'],' >>list.json
		fi
	done
	echo '"":[]}' >>list.json
elif [ -z "$2" ]; then
	if [ -f "$1.zip" ]; then
		echo "Already downloaded: $1";
		exit
	fi
	echo "Handling $1"
	#curl -s -S 'https://svc90.main.px.t-online.de/version/v1/diagnosis-keys/country/EUR/date/'$1'/hour' | jq -r '.[]|["./download.sh","'$1'",.]|@sh' | sh -e
	curl -s -S 'https://svc90.main.px.t-online.de/version/v1/diagnosis-keys/country/EUR/date/'$1 >"$1.zip"
	echo "Done $1"
else
	if [ -f "$1@$2.zip" ]; then
		echo "Already downloaded: $1@$2";
		exit
	fi
	echo "    Handling $1@$2"
	curl -s -S 'https://svc90.main.px.t-online.de/version/v1/diagnosis-keys/country/EUR/date/'$1'/hour/'$2 >"$1@$2.zip"
fi