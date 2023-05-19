package compresstool;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import keyexportformat.KeyExportFormat.TemporaryExposureKey;
import keyexportformat.KeyExportFormat.TemporaryExposureKey.ReportType;
import keyexportformat.KeyExportFormat.TemporaryExposureKeyExport;

public class CompressDayFiles {

	public static void main(String[] args) throws Exception {
		compress("EUR_day_", "EUR", new File("../history_EUR"));
		compress("DE_day_", "DE", new File("../history_DE"));
	}

	private static void compress(String prefix, String region, File dir) throws Exception {
		System.out.println("===" + dir);
		List<File> dayFiles = new ArrayList<>();
		for (File f : dir.listFiles()) {
			if (f.getName().matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}\\.zip")) {
				dayFiles.add(f);
			}
		}
		Collections.sort(dayFiles, Comparator.comparing(f -> f.getName()));
		try (DataOutputStream mainFile = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(prefix + "main.zzz")));
				DataOutputStream keysFile = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(prefix + "keys.zzz")));
				DataOutputStream sigFile = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(prefix + "sigs.zzz")))) {
			DateTimeFormatter ff = DateTimeFormatter.ofPattern("yyyy-MM-dd'.zip'");
			byte[] sigInfo = null;
			for (File dayFile : dayFiles) {
				System.out.println(dayFile.getName());
				long nameTimestamp = LocalDate.parse(dayFile.getName(), ff).atStartOfDay(ZoneId.of("UTC")).toInstant().getEpochSecond();
				ExposureFile df = ExposureFile.parseFile(dayFile);
				TemporaryExposureKeyExport ddata = df.getData();
				if (ddata.getBatchNum() != 1 || ddata.getBatchSize() != 1 || ddata.getKeysCount() < 1 || ddata.getKeysCount() > 16777215 || !region.equals(ddata.getRegion()))
					throw new IOException();
				if (ddata.getSignatureInfosCount() != 1)
					throw new IOException();
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				ddata.getSignatureInfos(0).writeTo(baos);
				if (sigInfo == null) {
					sigInfo = baos.toByteArray();
					ddata.getSignatureInfos(0).writeDelimitedTo(mainFile);
				} else if (!Arrays.equals(sigInfo, baos.toByteArray())) {
					throw new IOException(ddata.getSignatureInfos(0).toString());
				}
				File eurFile = new File("../history_EUR/", dayFile.getName());
				if (region.equals("DE") && eurFile.exists()) {
					TemporaryExposureKeyExport edata = ExposureFile.parseFile(eurFile).getData();

					if (ddata.getStartTimestamp() != edata.getStartTimestamp() || ddata.getEndTimestamp() != edata.getEndTimestamp())
						throw new IOException();

					if (dayFile.getName().equals("2020-11-07.zip")) {
						mainFile.writeByte(251);
					} else {
						mainFile.writeByte(250);
					}
					int lastKey = -1;
					List<TemporaryExposureKey> ekeys = edata.getKeysList();
					int flags = 0;
					if (!ddata.getKeys(0).hasDaysSinceOnsetOfSymptoms() && !ddata.getKeys(0).hasReportType() && edata.getKeys(0).hasDaysSinceOnsetOfSymptoms() && edata.getKeys(0).hasReportType()) {
						flags = 0x10000000;
						ekeys = new ArrayList<>(edata.getKeysCount());
						for (TemporaryExposureKey k : edata.getKeysList()) {
							ekeys.add(TemporaryExposureKey.newBuilder(k).clearDaysSinceOnsetOfSymptoms().clearReportType().build());
						}
					}
					mainFile.writeInt(flags | ddata.getKeysCount());
					for (TemporaryExposureKey key : ddata.getKeysList()) {
						boolean found = false;
						for (int j = lastKey + 1; j < ekeys.size(); j++) {
							if (ekeys.get(j).equals(key)) {
								found = true;
								if (j < 0 || j >= 0x01000000)
									throw new IOException("" + j);
								if (j - lastKey > 0 && j - lastKey < 0x100)
									mainFile.writeByte(j - lastKey);
								else
									mainFile.writeInt(j);
								lastKey = j;
								break;
							}
						}
						if (!found) {
							for (int j = 0; j < lastKey; j++) {
								if (ekeys.get(j).equals(key)) {
									found = true;
									lastKey = j;
									if (j < 0 || j >= 0x01000000)
										throw new IOException("" + j);
									mainFile.writeInt(j);
									break;
								}
							}
						}
						if (!found) {
							throw new IOException("MISSING");
						}
					}
				} else {
					int startHours = (int) ((ddata.getStartTimestamp() - nameTimestamp) / 3600);
					int endHours = (int) ((ddata.getEndTimestamp() - nameTimestamp) / 3600);
					if (startHours < 0 || startHours > 23 || endHours < 1 || endHours > 24 || ddata.getStartTimestamp() != nameTimestamp + startHours * 3600 || ddata.getEndTimestamp() != nameTimestamp + endHours * 3600)
						throw new IOException(startHours + "-" + endHours);

					if (dayFile.getName().equals("2020-11-07.zip")) {
						if (startHours != 0 || endHours != 24)
							throw new RuntimeException(startHours + "-" + endHours);
						mainFile.writeByte(254);
					} else if (startHours == 0 && endHours == 24) {
						mainFile.writeByte(253);
					} else {
						mainFile.writeByte(startHours);
						mainFile.writeByte(endHours);
					}
					mainFile.writeInt(ddata.getKeysCount());
					for (TemporaryExposureKey key : ddata.getKeysList()) {
						byte[] keyBytes = key.getKeyData().toByteArray();
						if (keyBytes.length != 16)
							throw new IOException("" + keyBytes.length);
						keysFile.write(keyBytes);
						boolean shortPeriod = key.getRollingPeriod() != 144;
						int dayOffset = (int) ((nameTimestamp / 600 - key.getRollingStartIntervalNumber()) / 144);
						if (key.getRollingStartIntervalNumber() != nameTimestamp / 600 - dayOffset * 144 || key.getRollingPeriod() <= 0 || key.getRollingPeriod() > 144 || dayOffset < 0 || dayOffset > 20)
							throw new IOException(dayOffset + ":" + key.getRollingPeriod());
						if (key.getDaysSinceOnsetOfSymptoms() > 2 || key.getDaysSinceOnsetOfSymptoms() < 0 || key.getTransmissionRiskLevel() < 1 || key.getTransmissionRiskLevel() > 8 || key.getReportType() == ReportType.REVOKED)
							throw new IOException(key.getDaysSinceOnsetOfSymptoms() + "/" + key.getTransmissionRiskLevel() + "/" + key.getReportType());
						// 21 (dayOffset) * 2 (Flag) * 3 (symp) * 8 (Risk) * 5 (Type) = 5040 - be lazy as we will need 2 bytes anyway.
						mainFile.writeByte((key.getTransmissionRiskLevel() - 1) + ((key.hasDaysSinceOnsetOfSymptoms() ? key.getDaysSinceOnsetOfSymptoms() : 3) << 3) + ((key.hasReportType() ? key.getReportType().getNumber() : 7) << 5));
						mainFile.writeByte(dayOffset + (shortPeriod ? 128 : 0));
						if (shortPeriod) {
							mainFile.writeByte(key.getRollingPeriod());
						}
					}
				}
				if (df.getSignature().length > 255)
					throw new IOException("Sig length " + df.getSignature());
				sigFile.writeByte(df.getSignature().length);
				sigFile.write(df.getSignature());
				sigFile.writeBoolean(df.isSwappedFiles());
				sigFile.writeLong(df.getFileTimes()[0]);
				sigFile.writeLong(df.getFileTimes()[1]);
			}
			mainFile.writeByte(255);
		}
		System.out.println("Done.");
	}
}
