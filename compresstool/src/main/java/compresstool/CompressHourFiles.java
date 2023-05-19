package compresstool;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import keyexportformat.KeyExportFormat.TemporaryExposureKey;
import keyexportformat.KeyExportFormat.TemporaryExposureKey.ReportType;
import keyexportformat.KeyExportFormat.TemporaryExposureKeyExport;

public class CompressHourFiles {

	public static void main(String[] args) throws Exception {
		compress("DE_hour_", new File("../history_DE"), "2020-10-27@1");
		compress("EUR_hour_", new File("../history_EUR"), "2020-10-28@5", "2020-11-03@3",
				"2020-11-04@3", "2020-11-05@3", "2020-11-08@5", "2020-11-09@5",
				"2020-11-10@4");
	}

	private static void compress(String prefix, File dir, String... exceptions) throws Exception {

		class KeyList {
			private List<TemporaryExposureKey> data;
		}

		Set<String> exceptionSet = new HashSet<>();
		for (String ex : exceptions) {
			exceptionSet.add(ex + ".zip");
		}
		System.out.println("===" + dir);
		Map<String, List<File>> days = new HashMap<>();
		for (File f : dir.listFiles()) {
			if (exceptionSet.contains(f.getName()))
				continue; // contains extra keys
			if (f.getName().matches(".*@[12]?[0-9]\\.zip")) {
				days.computeIfAbsent(f.getName().replaceFirst("@[12]?[0-9]\\.zip", ".zip"), x -> new ArrayList<>()).add(f);
			}
		}
		List<String> dayKeys = new ArrayList<>(days.keySet());
		Collections.sort(dayKeys);
		try (DataOutputStream mainFile = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(prefix + "main.zzz")));
				DataOutputStream sigFile = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(prefix + "sigs.zzz")))) {
			DateTimeFormatter ff = DateTimeFormatter.ofPattern("yyyy-MM-dd'.zip'");
			long lastIdx = -1;
			for (String day : dayKeys) {
				long idx = LocalDate.of(2020, 6, 1).until(LocalDate.parse(day, ff), ChronoUnit.DAYS);
				if (idx <= 0 || idx > 255)
					throw new RuntimeException("" + idx);
				if (idx != lastIdx + 1) {
					if (lastIdx != -1)
						mainFile.writeByte((int) lastIdx);
					mainFile.writeByte((int) idx);
				}
				lastIdx = idx;
			}
			mainFile.writeByte((int) lastIdx);
			mainFile.writeByte(0);
			for (String day : dayKeys) {
				System.out.println(day);
				ExposureFile df = ExposureFile.parseFile(new File(dir, day));
				TemporaryExposureKeyExport ddata = df.getData();
				KeyList[] hkeys = new KeyList[24];
				byte[][] hsigs = new byte[24][];
				long[][] hstamps = new long[24][];
				boolean[] hswapped = new boolean[24];
				boolean allDay = days.get(day).size() == 24;
				boolean almostAllDay = days.get(day).size() == 23;
				for (File f : days.get(day)) {
					int hour = Integer.parseInt(f.getName().replaceAll(".*@([12]?[0-9])\\.zip", "$1"));
					ExposureFile hf = ExposureFile.parseFile(f);
					TemporaryExposureKeyExport hdata = hf.getData();
					if (!allDay && !almostAllDay && hdata.getKeysCount() == 0)
						throw new IOException(f.getName());
					hkeys[hour] = new KeyList();
					hkeys[hour].data = new ArrayList<>(hdata.getKeysList());
					hsigs[hour] = hf.getSignature();
					hstamps[hour] = hf.getFileTimes();
					hswapped[hour] = hf.isSwappedFiles();
					if (!ddata.getRegion().equals(hdata.getRegion()) || hdata.getBatchNum() != 1 || hdata.getBatchSize() != 1)
						throw new IOException();
					if (!hdata.getSignatureInfosList().equals(ddata.getSignatureInfosList()))
						throw new IOException();
					if (hdata.getStartTimestamp() != (ddata.getStartTimestamp() / 86400 * 86400) + 3600 * hour)
						throw new IOException(hdata.getStartTimestamp() + "/" + ddata.getStartTimestamp() + "/" + hour);
					if (hdata.getEndTimestamp() != hdata.getStartTimestamp() + 3600)
						throw new IOException();
				}
				int lastSource = -1, lastCount = 0;
				for (TemporaryExposureKey kk : ddata.getKeysList()) {
					int source = 0;
					for (int i = 0; i < 24; i++) {
						if (hkeys[i] != null && !hkeys[i].data.isEmpty() && kk.equals(hkeys[i].data.get(0))) {
							source = i + 1;
							hkeys[i].data.remove(0);
							break;
						}
					}
					if (source == 0 && day.equals("2020-10-17.zip") && kk.hasReportType() && kk.hasDaysSinceOnsetOfSymptoms() && kk.getDaysSinceOnsetOfSymptoms() == 0 && kk.getReportType().getNumber() != 0) {
						TemporaryExposureKey kkk = TemporaryExposureKey.newBuilder(kk).clearReportType().clearDaysSinceOnsetOfSymptoms().build();
						for (int i = 0; i < 24; i++) {
							if (hkeys[i] != null && !hkeys[i].data.isEmpty() && kkk.equals(hkeys[i].data.get(0))) {
								source = i + 1 + 100;
								hkeys[i].data.remove(0);
								break;
							}
						}
					}
					if (source == 0 && day.equals("2020-11-20.zip") && kk.hasDaysSinceOnsetOfSymptoms() && kk.getDaysSinceOnsetOfSymptoms() != 0) {
						TemporaryExposureKey kkk = TemporaryExposureKey.newBuilder(kk).setReportType(ReportType.CONFIRMED_TEST).setDaysSinceOnsetOfSymptoms(0).build();
						for (int i = 0; i < 24; i++) {
							if (hkeys[i] != null && !hkeys[i].data.isEmpty() && kkk.equals(hkeys[i].data.get(0))) {
								source = i + 1 + 100;
								hkeys[i].data.remove(0);
								break;
							}
						}
					}
					if (source != lastSource) {
						if (lastCount != 0) {
							writeSourceAndCount(mainFile, lastSource, lastCount);
						}
						lastSource = source;
						lastCount = 0;
					}
					lastCount++;
				}
				if (lastCount != 0) {
					writeSourceAndCount(mainFile, lastSource, lastCount);
				}
				if (almostAllDay) {
					int missing = -1;
					for (int i = 0; i < 24; i++) {
						if (hkeys[i] == null) {
							if (missing == -1)
								missing = i;
							else
								throw new IOException(missing + "+" + i);
						}
					}
					if (missing == -1)
						throw new IOException();
					mainFile.writeByte(252);
					mainFile.writeByte(missing);
				} else {
					mainFile.writeByte(allDay ? 251 : 250);
				}
				for (int i = 0; i < 24; i++) {
					if (hkeys[i] != null && !hkeys[i].data.isEmpty()) {
						throw new IOException(i + ":" + hkeys[i].data.get(0));
					}
					if (hsigs[i] != null) {
						if (hsigs[i].length > 255)
							throw new RuntimeException("Sig length " + hsigs[i].length);
						sigFile.writeByte(hsigs[i].length);
						sigFile.write(hsigs[i]);
						sigFile.writeBoolean(hswapped[i]);
						sigFile.writeLong(hstamps[i][0]);
						sigFile.writeLong(hstamps[i][1]);
					}
				}
			}
		}
		System.out.println("Done.");
	}

	private static void writeSourceAndCount(DataOutputStream dos, int source, int count) throws IOException {
		int first, second;
		if (source >= 100) {
			if (count >= 1 && count <= 2) {
				first = (source - 100) * 10 + 7 + count;
				second = -1;
			} else if (count >= 3 && count < 36) {
				first = (source - 100) * 10;
				second = count + 220;
			} else {
				throw new RuntimeException(source + "/" + count);
			}
		} else {
			if (count >= 1 && count <= 7) {
				first = source * 10 + count;
				second = -1;
			} else if (count >= 8 && count < 220) {
				first = source * 10;
				second = count;
			} else {
				throw new RuntimeException(source + "/" + count);
			}
		}
		if (first < 0 || first > 255 || second < -1 || second > 255)
			throw new RuntimeException(first + "/" + second);
		dos.writeByte(first);
		if (second != -1)
			dos.writeByte(second);
	}
}
