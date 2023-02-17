/ CREATES THE .XLSX FILE WITH THE DATA FROM THE SERVIZI EROGATI CORRESPONDING TO THE SPECIFIC ENTE SERVICE
@Override
public ResponseEntity<InputStreamResource> createXlsx(Long idEnteService, ZonedDateTime initialDate, ZonedDateTime finalDate) {
        String FILE_NAME = "C:\\\\Users\\\\j.allaisufi\\\\Downloads\\\\REPORT.xlsx";

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet();
        List<ServizioErogatoCsvDTO> servizioErogatoCsvDTOS = this.convertFatturazioneEnteToCsv(idEnteService, initialDate, finalDate);

        int rowNum = 1;

        String[] headerValues = {
        "ENTE RIFERIMENTO",
        "STRUTTURA",
        "NOME",
        "COGNOME",
        "DESCRIZIONE",
        "CODICE FISCALE",
        "IMPONIBILE UNITARIO",
        "QUANTITÀ",
        "IMPONIBILE TOTALE",
        "IMPONIBILE TOTALE ASSENZE"
        };

        List<String> allColumns = new ArrayList<>();
        allColumns.addAll(Arrays.asList(headerValues));
        int headerColumnNr = 1;
        Row headerRow = sheet.createRow(0);
        for (String value : allColumns) {
        Cell cell = headerRow.createCell(headerColumnNr++);
        cell.setCellValue(value);
        }
        System.out.println("Creating excel");

        for (ServizioErogatoCsvDTO servizioErogatoCsvDTO : servizioErogatoCsvDTOS) {
        Row row = sheet.createRow(rowNum++);
        Cell numberCell = row.createCell(0);
        numberCell.setCellValue(rowNum-1);
        int colNum = 1;
        List<Object> values = new ArrayList<>();
        values.add(servizioErogatoCsvDTO.getEnte().getNome());
        values.add(servizioErogatoCsvDTO.getStructure());
        values.add(servizioErogatoCsvDTO.getPatientName());
        values.add(servizioErogatoCsvDTO.getPatientSurname());
        values.add(servizioErogatoCsvDTO.getServiceDescription());
        values.add(servizioErogatoCsvDTO.getFiscalCode());
        if (servizioErogatoCsvDTO.getDailyFee() != null) {
        values.add(servizioErogatoCsvDTO.getDailyFee());
        } else {
        values.add("0");
        }
        values.add(servizioErogatoCsvDTO.getPresentDays());
        if (servizioErogatoCsvDTO.getImponibile() != null) {
        values.add(servizioErogatoCsvDTO.getImponibile().setScale(2, BigDecimal.ROUND_UP).toString());
        } else {
        values.add("0");
        }
        if (servizioErogatoCsvDTO.getAbsenceImponibile() != null) {
        values.add(servizioErogatoCsvDTO.getAbsenceImponibile().setScale(2, BigDecimal.ROUND_UP).toString());
        } else {
        values.add("0");
        }
        for (Object field : values) {
        Cell cell = row.createCell(colNum++);
        if (field instanceof String) {
        cell.setCellValue((String) field);
        } else if (field instanceof Integer) {
        cell.setCellValue((Integer) field);
        } else if (field instanceof BigDecimal) {
        cell.setCellValue(((BigDecimal) field).toPlainString());
        }
        }
        }

        try {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        workbook.write(baos);
        workbook.close();
        baos.close();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=REPORT.xlsx");
        byte[] bytes = baos.toByteArray();
        InputStream inputStream = new ByteArrayInputStream(bytes);
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(inputStream));
        } catch (IOException e) {
        e.printStackTrace();
        }

        System.out.println("Done");

        return null;
        }