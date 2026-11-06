package com.rollercoaster.rax.compiler;

import com.rollercoaster.rax.ast.SqlDialect;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class TimeIntelligenceCompiler {

    public String compileTotalYtdBoundaryPredicate(
            String dateColumnSql,
            SqlDialect dialect,
            int fiscalYearStartMonth) {
        int validMonth = (fiscalYearStartMonth >= 1 && fiscalYearStartMonth <= 12) ? fiscalYearStartMonth : 1;
        String monthPadded = String.format("%02d", validMonth);

        if (dialect == SqlDialect.MYSQL) {
            if (validMonth == 1) {
                return dateColumnSql + " >= DATE_FORMAT(:currentDate, '%Y-01-01') AND " + dateColumnSql + " <= :currentDate";
            }
            return dateColumnSql + " >= CASE WHEN MONTH(:currentDate) >= " + validMonth
                    + " THEN DATE_FORMAT(:currentDate, '%Y-" + monthPadded + "-01')"
                    + " ELSE DATE_FORMAT(DATE_SUB(:currentDate, INTERVAL 1 YEAR), '%Y-" + monthPadded + "-01') END"
                    + " AND " + dateColumnSql + " <= :currentDate";
        } else {
            if (validMonth == 1) {
                return dateColumnSql + " >= CAST(strftime(:currentDate, '%Y-01-01') AS DATE) AND "
                        + dateColumnSql + " >= date_trunc('year', CAST(:currentDate AS DATE)) AND "
                        + dateColumnSql + " <= CAST(:currentDate AS DATE)";
            }
            return dateColumnSql + " >= CASE WHEN EXTRACT(MONTH FROM CAST(:currentDate AS DATE)) >= " + validMonth
                    + " THEN CAST(strftime(CAST(:currentDate AS DATE), '%Y-" + monthPadded + "-01') AS DATE)"
                    + " ELSE CAST(strftime(CAST(:currentDate AS DATE) - INTERVAL '1 year', '%Y-" + monthPadded + "-01') AS DATE) END"
                    + " AND " + dateColumnSql + " <= CAST(:currentDate AS DATE)";
        }
    }

    public String compileTotalMtdBoundaryPredicate(String dateColumnSql, SqlDialect dialect) {
        if (dialect == SqlDialect.MYSQL) {
            return dateColumnSql + " >= DATE_FORMAT(:currentDate, '%Y-%m-01') AND " + dateColumnSql + " <= :currentDate";
        }
        return dateColumnSql + " >= date_trunc('month', CAST(:currentDate AS DATE)) AND " + dateColumnSql + " <= CAST(:currentDate AS DATE)";
    }

    public String compileTotalQtdBoundaryPredicate(String dateColumnSql, SqlDialect dialect) {
        if (dialect == SqlDialect.MYSQL) {
            return "QUARTER(" + dateColumnSql + ") = QUARTER(:currentDate) AND YEAR(" + dateColumnSql + ") = YEAR(:currentDate) AND " + dateColumnSql + " <= :currentDate";
        }
        return dateColumnSql + " >= date_trunc('quarter', CAST(:currentDate AS DATE)) AND " + dateColumnSql + " <= CAST(:currentDate AS DATE)";
    }

    public String compileSamePeriodLastYearPredicate(String dateColumnSql, SqlDialect dialect) {
        if (dialect == SqlDialect.MYSQL) {
            return dateColumnSql + " BETWEEN DATE_SUB(:periodStartDate, INTERVAL 1 YEAR) AND DATE_SUB(:periodEndDate, INTERVAL 1 YEAR)";
        }
        return dateColumnSql + " BETWEEN (CAST(:periodStartDate AS DATE) - INTERVAL '1 year') AND (CAST(:periodEndDate AS DATE) - INTERVAL '1 year')";
    }

    public String compileDateAddPredicate(String dateColumnSql, int offset, String intervalUnit, SqlDialect dialect) {
        String unit = intervalUnit != null ? intervalUnit.trim().toUpperCase() : "MONTH";
        if (dialect == SqlDialect.MYSQL) {
            return dateColumnSql + " = DATE_ADD(:currentDate, INTERVAL " + offset + " " + unit + ")";
        }
        return dateColumnSql + " = (CAST(:currentDate AS DATE) + INTERVAL '" + offset + " " + unit.toLowerCase() + "')";
    }

    public LocalDate computeFiscalYearStartDate(LocalDate evaluationDate, int fiscalYearStartMonth) {
        int validMonth = (fiscalYearStartMonth >= 1 && fiscalYearStartMonth <= 12) ? fiscalYearStartMonth : 1;
        int year = evaluationDate.getMonthValue() >= validMonth
                ? evaluationDate.getYear()
                : evaluationDate.getYear() - 1;
        return LocalDate.of(year, validMonth, 1);
    }
}
