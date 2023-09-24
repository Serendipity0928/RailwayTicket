import com.spl.TicketPurchaseProcess;
import com.spl.config.TicketPurchaseConfig;

public class HelloSelenium {
    public static void main(String[] args){

        int allThreadNum = 1;

        TicketPurchaseConfig config = new TicketPurchaseConfig();
        config.setAllThreadNum(allThreadNum);
        config.setQueryDate("2023-10-03");
        config.setOpenSaleDate("2023-09-23 22:39:00");

        for (int i = 0; i < allThreadNum; i++) {
            new Thread(new TicketPurchaseProcess(i, config)).start();
        }
    }
}
