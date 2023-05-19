package compresstool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import keyexportformat.KeyExportFormat.TemporaryExposureKeyExport;

public class ExposureFile {
	private final TemporaryExposureKeyExport data;
	private final byte[] signature;
	private final boolean swappedFiles;
	private final long[] fileTimes;

	public ExposureFile(TemporaryExposureKeyExport data, byte[] signature, boolean swappedFiles, long[] fileTimes) {
		this.signature = signature;
		this.data = data;
		this.swappedFiles = swappedFiles;
		this.fileTimes = fileTimes;
	}

	public byte[] getSignature() {
		return signature;
	}

	public TemporaryExposureKeyExport getData() {
		return data;
	}

	public long[] getFileTimes() {
		return fileTimes;
	}

	public boolean isSwappedFiles() {
		return swappedFiles;
	}

	public void storeFile(OutputStream out) throws IOException {
		try (ZipOutputStream zos = new ZipOutputStream(out)) {
			ZipEntry sigFile = new ZipEntry("export.sig");
			sigFile.setTime(fileTimes[1]);
			if (swappedFiles) {
				zos.putNextEntry(sigFile);
				zos.write(signature);
			}
			ZipEntry mainFile = new ZipEntry("export.bin");
			mainFile.setTime(fileTimes[0]);
			zos.putNextEntry(mainFile);
			zos.write("EK Export v1    ".getBytes(StandardCharsets.ISO_8859_1));
			data.writeTo(zos);
			if (!swappedFiles) {
				zos.putNextEntry(sigFile);
				zos.write(signature);
			}
		}
	}

	public static ExposureFile parseFile(File file) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
			long[] fileTimes = new long[2];
			byte[] sig = null;
			boolean swappedFiles = false;

			ZipEntry ze = zis.getNextEntry();
			if (ze.getName().equals("export.sig")) {
				fileTimes[1] = ze.getTime();
				sig = readEntry(zis);
				swappedFiles = true;
				ze = zis.getNextEntry();
			}
			if (!ze.getName().equals("export.bin")) {
				throw new IOException("Unexpected file " + ze.getName() + " in " + file);
			}
			fileTimes[0] = ze.getTime();
			byte[] content = readEntry(zis);
			if (!new String(content, 0, 16, StandardCharsets.ISO_8859_1).equals("EK Export v1    "))
				throw new IOException("Invalid export.bin in " + file);
			TemporaryExposureKeyExport data = TemporaryExposureKeyExport.parseFrom(Arrays.copyOfRange(content, 16, content.length));

			ze = zis.getNextEntry();
			if (swappedFiles) {
				// already read
			} else if (ze.getName().equals("export.sig")) {
				fileTimes[1] = ze.getTime();
				sig = readEntry(zis);
			} else {
				throw new IOException("Unexpected file " + ze.getName() + " in " + file);
			}

			ze = zis.getNextEntry();
			if (ze != null) {
				throw new IOException("Unexpected file " + ze.getName() + " in " + file);
			}

			return new ExposureFile(data, sig, swappedFiles, fileTimes);
		}
	}

	private static byte[] readEntry(ZipInputStream zis) throws IOException {
		byte[] buf = new byte[4096];
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		int len;
		while ((len = zis.read(buf)) != -1) {
			baos.write(buf, 0, len);
		}
		return baos.toByteArray();
	}
}
