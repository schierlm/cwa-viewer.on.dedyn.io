var zip = new JSZip();
var BASEURL = "https://cors.eu.org/https://svc90.main.px.t-online.de/version/v1/diagnosis-keys/";

function decodeZip(url) {
	return fetch(url)
		.then(response => response.arrayBuffer())
		.then(buffer => Promise.all([zip.loadAsync(buffer), Promise.resolve(buffer.byteLength)]))
		.then(zipFile => Promise.all([protobuf.load("keyExportFormat.proto"), zipFile[0].file("export.bin").async("arraybuffer"), Promise.resolve(zipFile[1])]))
		.then(val => {
			var root = val[0], buffer = val[1];
			if (String.fromCharCode.apply(null, new Uint8Array(buffer.slice(0, 16))) != "EK Export v1    ") {
				return Promise.reject("Invalid file format");
			}
			var json = root.TemporaryExposureKeyExport.decode(new Uint8Array(buffer.slice(16)));
			json._keyType = root.TemporaryExposureKey;
			json._zipSize = val[2];
			return Promise.resolve(json);
		});
}

function dumpDetails(json, title, divId) {
	var h = '<div class="details"><h2>'+title+' ('+((json._zipSize+1023)/1024 | 0) +' KB)</h2><h3>Keys</h3><table><tr><th>Data</th><th>Risk</th><th>Start</th><th>Period</th><th>Graph</th></tr>';
	var stats = {}, minStart = Number.MAX_SAFE_INTEGER, maxStart = 0;
	for(var key of json.keys) {
		var start = key.rollingStartIntervalNumber;
		if (start > maxStart) maxStart = start;
		if (start < minStart) minStart = start;
	}
	for(var key of json.keys) {
		var start = key.rollingStartIntervalNumber;
		var graph = ("".padEnd((start-minStart)/144,'-'))+(key.rollingPeriod==144?"#":"?")+("".padEnd((maxStart-start)/144, '-'));
		var risk = ""+key.transmissionRiskLevel;
		if (key.reportType) {
			risk += " ["+key.daysSinceOnsetOfSymptoms + "d: " + json._keyType.toObject(key, {enums: String}).reportType + "]";
		}
		if (stats[risk] === undefined)
			stats[risk] = {};
		if (stats[risk][""+key.rollingStartIntervalNumber] === undefined)
			stats[risk][""+key.rollingStartIntervalNumber] = 1;
		else
			stats[risk][""+key.rollingStartIntervalNumber]++;
		h +='<tr><td><tt>'+[...key.keyData].map (b => b.toString(16).padStart(2, "0")).join(" ")+'</tt></td><td>'+risk;
		h += '</td><td>'+key.rollingStartIntervalNumber+'</td><td>'+key.rollingPeriod+'</td><td><tt>'+graph+'</tt></td></tr>';
	}
	delete json.keys;
	delete json._zipSize;
	delete json._keyType;
	var cols = Object.keys(stats).sort();
	var rows = Object.keys(Object.assign({}, ...Object.values(stats))).sort();
	h += '</table><h3>Key statistics</h3><table><tr><th>Start \\ Risk</th><th>'+cols.join('</th><th>')+'</th><th>Total</th></tr>';
	var total = 0;
	for(var row of rows) {
		var sum = 0;
		h += '<tr><td><b>'+row+'</b></td>';
		for (var col of cols) {
			var stat = stats[col][row] || 0;
			sum += stat;
			h+="<td>"+stat+"</td>";
		}
		h +='<td><b>'+sum+'</b></td></tr>';
		total += sum;
	}
	h += '<tr><td><b><i>Sum</i></b></td><td><i>'+cols.map(c => Object.values(stats[c]).reduce((x,y) => x+y)).join('</i></td><td><i>')+'</i></td>';
	h +='<td><b><i>'+total+'</i></b></td></tr></table><h3>Remaining data</h3><pre class="json">'+syntaxHighlight(json) + "</pre></div>";
	document.getElementById(divId).innerHTML = h;
}

function loadHour(ctry, day, hour) {
	decodeZip(BASEURL+"country/"+ctry+"/date/"+day+"/hour/"+hour).then(json => dumpDetails(json, ctry+" "+day+" "+hour+":00", "content-"+ctry+"@"+day+"T"+hour));
}

function loadDay(ctry, day) {
	decodeZip(BASEURL+"country/"+ctry+"/date/"+day).then(json => dumpDetails(json, ctry+" "+day, "content-"+ctry+"@"+day));
}
function getJSON(url) {
	return fetch(url).then(response => response.status == 404 ? Promise.resolve([]) : response.json());
}

function makeHourLinks(ctry, day, jHours) {
	var h = "";
	for (var hour of jHours.reverse()) {
		h += '<h4>'+hour+':00</h4><div id="content-'+ctry+'@'+day+'T'+hour+'"><button onclick="loadHour(\''+ctry+'\',\''+day+'\',\''+hour+'\')">Load hour data</button></div>';
	}
	return h;
}

function loadHours(ctry, day) {
	getJSON(BASEURL+"country/"+ctry+"/date/"+day+"/hour").then(jHours => {
		document.getElementById("content-"+ctry+"@"+day).innerHTML = makeHourLinks(ctry, day, jHours);
	});
}

function loadCountry(ctry) {
	var today = new Date().toISOString().substring(0,10);
	Promise.all([getJSON(BASEURL+"country/"+ctry+"/date"), getJSON(BASEURL+"country/"+ctry+"/date/"+today+"/hour")])
	.then(jsons => {
		var jDays = jsons[0], jHours = jsons[1];
		var h = "";
		if (jHours.length > 0) {
			h +='<h3>'+today+'</h3>';
			h += makeHourLinks(ctry, today, jHours);
		}
		for (var day of jDays.reverse()) {
			h +='<h3>'+day+'</h3><div id="content-'+ctry+"@"+day+'"><button onclick="loadDay(\''+ctry+'\',\'' +day+'\')">Load day data</button> <button onclick="loadHours(\''+ctry+'\',\'' +day+'\')">Load hours data</button></div>'
		}
		document.getElementById("content-"+ctry).innerHTML = h;
	});
}

var historicJSON = {};

function loadOldHour(ctry, day, hour) {
	decodeZip("./history_"+ctry+"/"+day+"@"+hour+".zip").then(json => dumpDetails(json, ctry+" "+day+" "+hour+":00", "ocontent-"+ctry+"@"+day+"T"+hour));
}

function loadOldDay(ctry, day) {
	decodeZip("./history_"+ctry+"/"+day+".zip").then(json => dumpDetails(json, ctry+" "+day, "ocontent-"+ctry+"@"+day));
}

function makeOldHourLinks(ctry, day, jHours) {
	var h = "";
	for (var hour of jHours.reverse()) {
		h += '<h4>'+hour+':00</h4><div id="ocontent-'+ctry+'@'+day+'T'+hour+'"><button onclick="loadOldHour(\''+ctry+'\',\''+day+'\',\''+hour+'\')">Load old hour data</button></div>';
	}
	return h;
}

function loadOldHours(ctry, day) {
	var jHours = historicJSON[ctry][day].filter(x => x !== true);
	document.getElementById("ocontent-"+ctry+"@"+day).innerHTML = makeOldHourLinks(ctry, day, jHours);
}

function loadOldCountry(ctry) {
	getJSON("history_"+ctry+"/list.json").then(json => {
		delete json[""];
		historicJSON[ctry] = json;
		var h = "";
		for (var day of Object.keys(json).sort().reverse()) {
			h +='<h3>'+day+'</h3><div id="ocontent-'+ctry+"@"+day+'"><button onclick="loadOldDay(\''+ctry+'\',\'' +day+'\')">Load old day data</button> <button onclick="loadOldHours(\''+ctry+'\',\'' +day+'\')">Load old hours data</button></div>'
		}
		document.getElementById("ocontent-"+ctry).innerHTML = h;
	});
}

function setContent(h) {
	h += '<h2>Old-DE</h2><p>Old data is from a mirror that gets updated in infrequent intervals, as the official server does not provide old files.</p><div id="ocontent-DE"><button onclick="loadOldCountry(\'DE\')">Load old country data</button></div>';
	h += '<h2>Old-EUR</h2><p>Old data is from a mirror that gets updated in infrequent intervals, as the official server does not provide old files.</p><div id="ocontent-EUR"><button onclick="loadOldCountry(\'EUR\')">Load old country data</button></div>';
	document.getElementById("content").innerHTML = h;
}

window.onload = function() {
	getJSON(BASEURL + "country").then(json => {
		var h = "";
		for(var ctry of json) {
			h += '<h2>'+ctry+'</h2><div id="content-'+ctry+'"><button onclick="loadCountry(\''+ctry+'\')">Load country data</button></div>';
		}
		setContent(h);
	}).catch(err => {
		setContent("<h2>Service not available</h2><p>Historic content below:</p>");
	});
};

// https://stackoverflow.com/a/7220510/90203
function syntaxHighlight(json) {
    if (typeof json != 'string') {
         json = JSON.stringify(json, undefined, 2);
    }
    json = json.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return json.replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, function (match) {
        var cls = 'number';
        if (/^"/.test(match)) {
            if (/:$/.test(match)) {
                cls = 'key';
            } else {
                cls = 'string';
            }
        } else if (/true|false/.test(match)) {
            cls = 'boolean';
        } else if (/null/.test(match)) {
            cls = 'null';
        }
        return '<span class="' + cls + '">' + match + '</span>';
    });
}
