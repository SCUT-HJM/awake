package com.example.awake.data.local

/**
 * ���ϴ�ѧ��ͬУ��ʹ�õĽڴα�ź�ʱ�䡣
 *
 * ���񷵻صĿγ̽ڴ���ʹ��ԭ��ţ����緬خУ������ 5��9����
 * �������Ҳ����ԭ��ţ��α���Ⱦʱ�����һһ��Ӧ��
 */
enum class JnuCampus(val displayName: String) {
    MAIN("����У��"),
    PANYU("��خУ��");

    val configs: List<PeriodConfigEntity>
        get() = when (this) {
            MAIN -> listOf(
                PeriodConfigEntity(period = 1, startTime = "08:00", endTime = "08:45"),
                PeriodConfigEntity(period = 2, startTime = "08:55", endTime = "09:40"),
                PeriodConfigEntity(period = 3, startTime = "10:00", endTime = "10:45"),
                PeriodConfigEntity(period = 4, startTime = "10:55", endTime = "11:40"),
                PeriodConfigEntity(period = 5, startTime = "12:40", endTime = "13:25", emptyType = PeriodConfigEntity.EMPTY_LUNCH),
                PeriodConfigEntity(period = 6, startTime = "13:35", endTime = "14:20", emptyType = PeriodConfigEntity.EMPTY_LUNCH),
                PeriodConfigEntity(period = 7, startTime = "14:30", endTime = "15:15"),
                PeriodConfigEntity(period = 8, startTime = "15:25", endTime = "16:10"),
                PeriodConfigEntity(period = 9, startTime = "16:20", endTime = "17:05"),
                PeriodConfigEntity(period = 10, startTime = "19:00", endTime = "19:45", emptyType = PeriodConfigEntity.EMPTY_EVENING),
                PeriodConfigEntity(period = 11, startTime = "19:55", endTime = "20:40"),
                PeriodConfigEntity(period = 12, startTime = "20:50", endTime = "21:35"),
                PeriodConfigEntity(period = 13, startTime = "20:50", endTime = "21:35"),
                PeriodConfigEntity(period = 14, startTime = "21:45", endTime = "22:30")
            )
            PANYU -> listOf(
                PeriodConfigEntity(period = 1, startTime = "08:40", endTime = "09:25"),
                PeriodConfigEntity(period = 2, startTime = "09:35", endTime = "10:20"),
                PeriodConfigEntity(period = 3, startTime = "10:30", endTime = "11:15"),
                PeriodConfigEntity(period = 4, startTime = "11:25", endTime = "12:10"),
                PeriodConfigEntity(period = 5, startTime = "12:10", endTime = "14:00", emptyType = PeriodConfigEntity.EMPTY_LUNCH),
                PeriodConfigEntity(period = 6, startTime = "14:00", endTime = "14:45"),
                PeriodConfigEntity(period = 7, startTime = "14:55", endTime = "15:40"),
                PeriodConfigEntity(period = 8, startTime = "15:50", endTime = "16:35"),
                PeriodConfigEntity(period = 9, startTime = "16:35", endTime = "18:30", emptyType = PeriodConfigEntity.EMPTY_EVENING),
                PeriodConfigEntity(period = 10, startTime = "18:30", endTime = "19:15"),
                PeriodConfigEntity(period = 11, startTime = "19:25", endTime = "20:10"),
                PeriodConfigEntity(period = 12, startTime = "20:20", endTime = "21:05")
            )
        }
}

