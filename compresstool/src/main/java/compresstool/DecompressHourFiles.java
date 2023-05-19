package compresstool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import keyexportformat.KeyExportFormat.TemporaryExposureKey;
import keyexportformat.KeyExportFormat.TemporaryExposureKey.ReportType;
import keyexportformat.KeyExportFormat.TemporaryExposureKeyExport;

public class DecompressHourFiles {
	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			decompress("../history_DE", "DE_hour_main.zzz", "DE_hour_sigs.zzz");
			decompress("../history_EUR", "EUR_hour_main.zzz", "EUR_hour_sigs.zzz");
		} else if (args.length == 2) {
			decompress(args[0], args[1], null);
		} else if (args.length == 3) {
			decompress(args[0], args[1], args[2]);
		} else {
			System.out.println("Usage: java compresstool.DecompressHourFiles <path> <mainfile> [<sigfile>]");
		}
	}

	private static void decompress(String path, String mainFile, String sigFile) throws IOException {
		System.out.println("===" + path);
		DataInputStream sigIS = sigFile == null || !new File(sigFile).exists() ? null : new DataInputStream(new BufferedInputStream(new FileInputStream(sigFile)));
		try (DataInputStream mainIS = new DataInputStream(new BufferedInputStream(new FileInputStream(mainFile)))) {
			List<int[]> ranges = new ArrayList<>();
			int from = mainIS.readByte() & 0xff;
			while (from != 0) {
				int to = mainIS.readByte() & 0xff;
				ranges.add(new int[] { from, to });
				from = mainIS.readByte() & 0xff;
			}
			DateTimeFormatter ff = DateTimeFormatter.ofPattern("yyyy-MM-dd'.zip'");
			for (int[] range : ranges) {
				for (int dd = range[0]; dd <= range[1]; dd++) {
					String day = LocalDate.of(2020, 6, 1).plusDays(dd).format(ff);
					System.out.println(day);
					ExposureFile df = ExposureFile.parseFile(new File(path, day));
					TemporaryExposureKeyExport ddata = df.getData();
					TemporaryExposureKeyExport.Builder[] hdata = new TemporaryExposureKeyExport.Builder[24];
					int dindex = 0;
					int[] hourAndCount = readHourAndCount(mainIS);
					while (hourAndCount.length == 2) {
						int hour_ = hourAndCount[0], count = hourAndCount[1];
						for (int ii = 0; ii < count; ii++) {
							TemporaryExposureKey key = ddata.getKeys(dindex++);
							if (hour_ == 0)
								continue;
							if (hour_ >= 100) {
								if (day.equals("2020-10-17.zip")) {
									key = TemporaryExposureKey.newBuilder(key).clearReportType().clearDaysSinceOnsetOfSymptoms().build();
								} else if (day.equals("2020-11-20.zip")) {
									key = TemporaryExposureKey.newBuilder(key).setReportType(ReportType.CONFIRMED_TEST).setDaysSinceOnsetOfSymptoms(0).build();
								} else {
									throw new IOException(day);
								}
							}
							int hour = hour_ % 100 - 1;
							if (hdata[hour] == null) {
								long startTimestamp = (ddata.getStartTimestamp() / 86400 * 86400) + 3600 * hour;
								hdata[hour] = TemporaryExposureKeyExport.newBuilder(ddata).clearKeys().setStartTimestamp(startTimestamp).setEndTimestamp(startTimestamp + 3600);
							}
							hdata[hour].addKeys(key);
						}
						hourAndCount = readHourAndCount(mainIS);
					}
					boolean allDay = hourAndCount[0] == 251 || hourAndCount[0] < 24;
					int missingHour = hourAndCount[0] < 24 ? hourAndCount[0] : -1;
					if (dindex != ddata.getKeysCount())
						throw new IOException(dindex + " != " + ddata.getKeysCount());
					for (int i = 0; i < 24; i++) {
						if (allDay && i != missingHour && hdata[i] == null) {
							long startTimestamp = (ddata.getStartTimestamp() / 86400 * 86400) + 3600 * i;
							hdata[i] = TemporaryExposureKeyExport.newBuilder(ddata).clearKeys().setStartTimestamp(startTimestamp).setEndTimestamp(startTimestamp + 3600);
						}
						if (hdata[i] != null) {
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
							try (OutputStream out = new BufferedOutputStream(new FileOutputStream(new File(path, day.replace(".zip", "@") + i + ".zip")))) {
								new ExposureFile(hdata[i].build(), hsig, hswapped, hstamps).storeFile(out);
							}
						}
					}
				}
			}
			if (mainIS.read() != -1)
				throw new IOException("" + mainIS.available());
			if (sigIS != null && sigIS.read() != -1)
				throw new IOException("" + sigIS.available());
		} finally {
			if (sigIS != null)
				sigIS.close();
		}
	}

	private static int[] readHourAndCount(DataInputStream dis) throws IOException {
		int first = dis.readByte() & 0xff;
		if (first == 250 || first == 251) {
			return new int[] { first };
		} else if (first == 252) {
			return new int[] { dis.readByte() & 0xff};
		} else if (first % 10 == 0) {
			int second = dis.readByte() & 0xff;
			if (second > 220) {
				return new int[] { 100 + first / 10, second - 220 };
			} else {
				return new int[] { first / 10, second };
			}
		} else if (first % 10 >= 8) {
			return new int[] { 100 + first / 10, first % 10 - 7 };
		} else {
			return new int[] { first / 10, first % 10 };
		}
	}
}
