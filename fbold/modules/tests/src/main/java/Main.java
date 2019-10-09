import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Main {

    static int ThirdLargest(int[] arr) {
        // First, sort the array. O(n log(n)) time
        Arrays.sort(arr);

        // Helper Set to keep track of how many distinct items we have seen.
        Set<Integer> distinctItems = new HashSet<>();

        // Iterate through the array backwards. On each iteration, add to the
        // distinctItems set. If the size of the set is 3 after adding the
        // item, then we've found our target.
        for (int i = arr.length - 1; i >= 0; i--) {
            distinctItems.add(arr[i]);
            if (distinctItems.size() == 3) {
                return arr[i];
            }
        }

        // Invalid state.
        return -1;
    }

    /**
     * DaysBetween iterates through every month (inclusive) between the first and second date
     * chronologically. That is, if both dates are equal, there will be one iteration for the
     * containing month.
     */
    static int DaysBetween(int year1, int month1, int day1, int year2, int month2, int day2) throws Exception {
        int days = 0;
        int currentYear = year1;
        int currentMonth = month1;

        // Loop through every month in the range. Count the number of days within the range
        // for the current month on each iteration.
        while ((currentYear < year2) || (currentYear == year2 && currentMonth <= month2)) {
            // The lower bound is day1 for the initial month, and the first day for
            // all others.
            int startDay = currentYear == year1 && currentMonth == month1 ? day1 : 1;

            // The upper bound is day2 for the last month, and the number of days in
            // the month + 1 for all others.
            int endDay = currentYear == year2 && currentMonth == month2 ? day2 :
                    DaysInMonth(currentMonth, currentYear) + 1;

            // Update the days counter with the difference.
            days += (endDay - startDay);

            // After counting days for the current month, step to the next month.
            if (currentMonth == 12) {
                currentYear++;
                currentMonth = 1;
            } else {
                currentMonth++;
            }
        }

        return days;
    }

    static int DaysInMonth(int month, int year) throws Exception {
        return 30;
    }

    public static void main(String[] args) throws Exception {
        int[] arr = {3, 12, 14, 6, 8, 1, 12};

        System.out.println(ThirdLargest(arr));

        System.out.println(DaysBetween(2011, 12, 1, 2011, 12, 1));
    }
}
