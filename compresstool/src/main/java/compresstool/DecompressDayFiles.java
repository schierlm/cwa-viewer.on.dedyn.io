package compresstool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.protobuf.ByteString;

import keyexportformat.KeyExportFormat.SignatureInfo;
import keyexportformat.KeyExportFormat.TemporaryExposureKey;
import keyexportformat.KeyExportFormat.TemporaryExposureKey.ReportType;
import keyexportformat.KeyExportFormat.TemporaryExposureKeyExport;

public class DecompressDayFiles {
	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			decompress("../history_EUR", "EUR", "2020-10-05.zip", "EUR_day_main.zzz", "EUR_day_keys.zzz", "EUR_day_sigs.zzz");
			decompress("../history_DE", "DE", "2020-06-23.zip", "DE_day_main.zzz", "DE_day_keys.zzz", "DE_day_sigs.zzz");
		} else if (args.length == 4) {
			decompress(args[0], args[1], args[2], args[3], null, null);
		} else if (args.length == 5) {
			decompress(args[0], args[1], args[2], args[3], args[4], null);
		} else if (args.length == 6) {
			decompress(args[0], args[1], args[2], args[3], args[4], args[5]);
		} else {
			System.out.println("Usage: java compresstool.DecompressDayFiles <path> <region> <startName> <mainfile> [<keysfile> [<sigfile>]]");
		}
	}

	private static void decompress(String path, String region, String startName, String mainFile, String keysFile, String sigFile) throws IOException {
		System.out.println("===" + path);
		int keyIndex = region.length() * 0x02000000;
		DataInputStream keysIS = keysFile == null || !new File(keysFile).exists() ? null : new DataInputStream(new BufferedInputStream(new FileInputStream(keysFile)));
		DataInputStream sigIS = sigFile == null || !new File(sigFile).exists() ? null : new DataInputStream(new BufferedInputStream(new FileInputStream(sigFile)));
		try (DataInputStream mainIS = new DataInputStream(new BufferedInputStream(new FileInputStream(mainFile)))) {
			DateTimeFormatter ff = DateTimeFormatter.ofPattern("yyyy-MM-dd'.zip'");
			LocalDate startTime = LocalDate.parse(startName, ff).minus(1, ChronoUnit.DAYS);
			SignatureInfo sigInfo = SignatureInfo.parseDelimitedFrom(mainIS);
			int tag = mainIS.readByte() & 0xFF;
			while (tag != 255) {
				if (tag == 251 || tag == 254) {
					tag--;
					startTime = startTime.plus(1, ChronoUnit.DAYS);
				}
				startTime = startTime.plus(1, ChronoUnit.DAYS);
				long timestamp = startTime.atStartOfDay(ZoneId.of("UTC")).toInstant().getEpochSecond();
				TemporaryExposureKeyExport.Builder odataB = TemporaryExposureKeyExport.newBuilder();
				odataB.setBatchNum(1).setBatchSize(1).setRegion(region).addSignatureInfos(sigInfo);
				if (tag == 250) { // country differences
					File eurFile = new File("../history_EUR/", startTime.format(ff));
					TemporaryExposureKeyExport edata = ExposureFile.parseFile(eurFile).getData();
					int keysCount = mainIS.readInt();
					boolean maskKeys = false;
					if (keysCount >= 0x10000000) {
						maskKeys = true;
						keysCount -= 0x10000000;
					}
					odataB.setStartTimestamp(edata.getStartTimestamp()).setEndTimestamp(edata.getEndTimestamp());
					int lastKey = -1;
					for (int i = 0; i < keysCount; i++) {
						int keyIdx = mainIS.readByte() & 0xFF;
						if (keyIdx == 0) {
							int ch2 = mainIS.readByte() & 0xFF;
							int ch3 = mainIS.readByte() & 0xFF;
							int ch4 = mainIS.readByte() & 0xFF;
							keyIdx = ((keyIdx << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
						} else {
							keyIdx += lastKey;
						}
						TemporaryExposureKey key = edata.getKeys(keyIdx);
						if (maskKeys) {
							key = TemporaryExposureKey.newBuilder(key).clearDaysSinceOnsetOfSymptoms().clearReportType().build();
						}
						odataB.addKeys(key);
						lastKey = keyIdx;
					}
				} else {
					int startHours, endHours;
					if (tag == 253) {
						startHours = 0;
						endHours = 24;
					} else if (tag >= 0 && tag <= 23) {
						startHours = tag;
						endHours = mainIS.readByte() & 0xFF;
					} else {
						throw new IOException(""+tag);
					}
					odataB.setStartTimestamp(timestamp + startHours * 3600).setEndTimestamp(timestamp + endHours * 3600);
					int keysCount = mainIS.readInt();
					for (int i = 0; i < keysCount; i++) {
						TemporaryExposureKey.Builder keyB = TemporaryExposureKey.newBuilder();
						keyIndex++;
						if (keyIndex < 0)
							throw new IOException();
						byte[] keyBytes = new byte[16];
						if (keysIS != null) {
							keysIS.readFully(keyBytes);
						} else {
							ByteBuffer.wrap(keyBytes).putInt(keyIndex);
						}
						keyB.setKeyData(ByteString.copyFrom(keyBytes));
						int byte1 = mainIS.readByte() & 0xFF;
						int byte2 = mainIS.readByte() & 0xFF;
						keyB.setTransmissionRiskLevel((byte1 & 0x07) + 1);
						if (((byte1 & 0x18) >>> 3) != 3)
							keyB.setDaysSinceOnsetOfSymptoms((byte1 & 0x18) >>> 3);
						if ((byte1 >>> 5) != 7) {
							//System.out.println(byte1 >>> 5);
							keyB.setReportType(ReportType.forNumber(byte1 >>> 5));
						}
						if (byte2 >= 128) {
							keyB.setRollingPeriod(mainIS.readByte() & 0xFF);
							byte2 -= 128;
						} else {
							keyB.setRollingPeriod(144);
						}
						int dayOffset = byte2;
						keyB.setRollingStartIntervalNumber((int) (timestamp / 600) - dayOffset * 144);
						odataB.addKeys(keyB.build());
					}
				}

				boolean hswapped = false;
				long[] hstamps = new long[2];
				byte[] hsig = new byte[0];
				if (sigIS != null) {
					hsig = new byte[sigIS.readByte() & 0xFF];
					sigIS.readFully(hsig);
					hswapped = sigIS.readBoolean();
					hstamps[0] = sigIS.readLong();
					hstamps[1] = sigIS.readLong();
				}
				String name = startTime.format(ff);
				System.out.println(name+": " + odataB.getKeysCount());
				try (OutputStream out = new BufferedOutputStream(new FileOutputStream(new File(path, name)))) {
					new ExposureFile(odataB.build(), hsig, hswapped, hstamps).storeFile(out);
				}
				tag = mainIS.readByte() & 0xFF;
			}
			if (mainIS.read() != -1)
				throw new IOException("" + mainIS.available());
			if (keysIS != null && keysIS.read() != -1)
				throw new IOException("" + keysIS.available());
			if (sigIS != null && sigIS.read() != -1)
				throw new IOException("" + sigIS.available());
		} finally {
			if (keysIS != null)
				keysIS.close();
			if (sigIS != null)
				sigIS.close();
		}
	}
}
