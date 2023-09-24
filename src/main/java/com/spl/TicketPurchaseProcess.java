package com.spl;

import com.spl.config.TicketPurchaseConfig;
import com.spl.exception.TicketProcessException;
import com.spl.template.RailwayTemplate;
import com.spl.util.DateUtil;
import graphql.com.google.common.collect.Sets;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TicketPurchaseProcess implements Runnable {

    private static volatile Set<String> alreadyRetryTrains = Sets.newHashSet();

    private TicketPurchaseConfig config;

    private int baseIndex;

    public TicketPurchaseProcess(int baseIndex, TicketPurchaseConfig config) {
        this.baseIndex = baseIndex;
        this.config = config;
    }

    @Override
    public void run() {
        // 初始化当前浏览器
        WebDriver driver = new ChromeDriver();
        driver.manage().window().maximize();

        try {
            // 登录
            doLogin(driver);

            // 查询预定信息
            doQueryTrainBookingInfo(driver);

        } catch (TicketProcessException ticketExp ){
            printUtil("车票购买发生已知异常: " + ticketExp.getMessage());
        } catch (Exception e) {
            printUtil("车票购买发生未知异常!");
            e.printStackTrace();
        } finally {
//            driver.quit();
        }
    }

    private void doLogin(WebDriver driver) {
        driver.get("https://kyfw.12306.cn/otn/resources/login.html");
        WebElement textBox =  driver.findElement(By.className("login-hd-account"));
        textBox.click();

        try {
            WebDriverWait webDriverWait = new WebDriverWait(driver, Duration.ofSeconds(10), Duration.ofMillis(500));
            webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.className("welcome-name")));
        } catch (TimeoutException e) {
            printUtil("登录超时...");
            throw new TicketProcessException("登录超时...");
        }
    }

    private void doQueryTrainBookingInfo(WebDriver driver) throws Exception {
        // 进入页面
        String leftTicketUrl = RailwayTemplate.LEFT_TICKET_URL_TEMPLATE
                .replace("{$DATE}", this.config.getQueryDate());
        driver.get(leftTicketUrl);
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(10));

        // ① 校验预定页面
        checkBookingPackage(driver);

        // ② 筛选二等座、显示全部可预订车次
        WebElement textBox =  driver.findElement(By.id("cc_seat_type_O_check"));
        textBox.click();
        WebElement textBox1 =  driver.findElement(By.id("avail_ticket"));
        textBox1.click();

        // ③ 校验开售时间[未设置默认为0]
        long openSaleTimestamp = DateUtil.convertDateToStamp(this.config.getOpenSaleDate());
        long curTimestamp = System.currentTimeMillis();
        if(openSaleTimestamp - curTimestamp > 3000) {
            // 时间太长没必要一直刷新，最近3秒开始刷新
            printUtil("开售时间(" + this.config.getOpenSaleDate()  + ")还有很久，睡眠中...");
            Thread.sleep(openSaleTimestamp - curTimestamp - 3000);
            printUtil("时间已到，苏醒吧...");
        }

        while (true) {
            List<WebElement> bookTicketButtons  = driver.findElements(By.xpath("//a[contains(@onclick, 'check')]"));
            if(bookTicketButtons == null || bookTicketButtons.size() == 0) {
                long curStamp = System.currentTimeMillis();
                if(curStamp < openSaleTimestamp + 3000) {
                    // 还未到时间,休眠0.5s后，点击查询刷新
                    printUtil("当前页面，还未查询到车次");
                    Thread.sleep(500);
                    WebElement queryTicketBtn = driver.findElement(By.id("query_ticket"));
                    queryTicketBtn.click();
                    continue;
                }
                printUtil("确实找不到可购买的车次，直接退出！");
                return;
            }

            for (int i = 0; i < bookTicketButtons.size(); i++) {
                if(i % this.config.getAllThreadNum() != this.baseIndex) {
                    continue;
                }
                WebElement bookTicketButton = bookTicketButtons.get(i);
                List<WebElement> secondElements = bookTicketButton.findElements(By.xpath("./../../child::*[contains(@aria-label, '二等座')]"));
                if(secondElements == null || secondElements.size() == 0) {  // 无二等座
                    continue;
                }
                WebElement secondElement = secondElements.get(0);   // 二等座元素

                Object[] trainInfoArr = parseTrainSecondSeatInfo(secondElement.getAttribute("aria-label"));
                if(trainInfoArr == null
                        || StringUtils.isBlank((String) trainInfoArr[0])
                        || (int) trainInfoArr[1] < 2
                        || alreadyRetryTrains.contains((String) trainInfoArr[0])) {
                    continue;
                }

                printUtil("查找到符合要求的车次：" + secondElement.getAttribute("aria-label"));
                bookTicketButton.click();

                if(doPurchaseTickets(driver)) {
                    return;
                }

                driver.navigate().back();
                break; // 这里是直接return哦
            }
            printUtil("任务执行正常结束，遗憾未购买到车票");
            return;
        }

    }

    private void checkBookingPackage(WebDriver driver) {
        List<WebElement> logoutELEs = driver.findElements(By.id("J-header-logout"));
        if(CollectionUtils.isEmpty(logoutELEs)) {
            throw new TicketProcessException("预定页面尚未登录~");
        }

        List<WebElement> queryButtonELEs = driver.findElements(By.id("query_ticket"));
        if(CollectionUtils.isEmpty(queryButtonELEs)) {
            throw new TicketProcessException("预定页面没有找到查询按钮~");
        }
    }

    private Object[] parseTrainSecondSeatInfo(String seatInfo) {
        if(seatInfo == null || seatInfo.length() == 0) {
            return null;
        }

        // 匹配车次号和候补信息
        Pattern pattern = Pattern.compile("([A-Z]\\d+)次列车，二等座.*余票(\\S+)");
        Matcher matcher = pattern.matcher(seatInfo);
        if (matcher.find()) {
            String trainNumber = matcher.group(1);
            String remainInfo = matcher.group(2);
            if(StringUtils.equals("有", remainInfo)) {
                remainInfo = "100";
            }
            if(StringUtils.isNumeric(remainInfo)) {
                return new Object[] {trainNumber, Integer.parseInt(remainInfo)};
            }
        }
        return null;
    }

    private boolean doPurchaseTickets(WebDriver driver) throws InterruptedException {
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));

        // ① 校验预览页
        checkForOrderPreviewPage(driver);

        // ② 选中乘客信息
        WebElement element = driver.findElement(By.xpath("//label[text()='孙培林']"));
        element.click();
        WebElement element1 = driver.findElement(By.xpath("//label[text()='范文静']"));
        element1.click();

        // ③ 校验座位选择option--二等座
        WebElement seatTypeSelect = driver.findElement(By.id("seatType_1"));
        Select select = new Select(seatTypeSelect);
        WebElement curSelectedOption = select.getFirstSelectedOption();
        if(!curSelectedOption.getText().contains("二等座")) {
            boolean hasSecondSeat = false;
            for (WebElement option : select.getOptions()) {
                String optionText = option.getText();
                if(optionText.contains("二等座")) {
                    select.selectByVisibleText(optionText);
                    hasSecondSeat = true;
                    break;
                }
            }
            if(!hasSecondSeat) {
                printUtil("无二等座选项，当前车次略过!");
                return false;
            }
        }

        WebElement submitOrderButton = driver.findElement(By.id("submitOrder_id"));
        submitOrderButton.click();

        // 弹窗
        try {
            WebDriverWait webDriverWait = new WebDriverWait(driver, Duration.ofSeconds(3), Duration.ofMillis(50));
            webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.id("sy_ticket_num_id")));
        } catch (TimeoutException e) {
            printUtil("弹窗超时...，当前车次略过!");
            return false;
        }

        WebElement ticketNumEle = driver.findElement(By.id("sy_ticket_num_id"));
        WebElement numEle = ticketNumEle.findElement(By.xpath(".//strong"));
        String numEleText = numEle.getText();
        if(!StringUtils.isNumeric(numEleText)) {
            printUtil("余票信息不是数字，当前车次略过！");
            return false;
        }
        int submitTicketNum = Integer.parseInt(numEleText);
        if(submitTicketNum < 2) {
            printUtil("提单弹窗提示车票不足，剩余车票个数：" + numEleText);
            return false;
        }
        printUtil("提单弹窗提示当前车次剩余车票为：" + numEleText);

        // 尝试确认提交订单
        WebElement confirmOrderButton = driver.findElement(By.id("qr_submit_id"));
        try {

            int maxClickTime = 0;
            while (confirmOrderButton.isDisplayed() && maxClickTime++ < 20) {
                String classValue = confirmOrderButton.getAttribute("class");
                boolean readyToClick = classValue.contains("btn92s");
                if(readyToClick) {
                    break;
                }

                Thread.sleep(500);
            }
            if(maxClickTime > 0) {
                confirmOrderButton.click();
                printUtil("车次提单成功." + maxClickTime);
                return true;
            }

            return false;
        } catch (Exception ex) {
            printUtil("提单操作异常！");
            throw ex;
        }

    }

    private void checkForOrderPreviewPage(WebDriver driver) {
        List<WebElement> passengerInfos = driver.findElements(By.id("normal_passenger_id"));
        if(CollectionUtils.isEmpty(passengerInfos)) {  // 无乘客信息，应该是没有跳转到当前页面
            // 检查登录模态框状态
            List<WebElement> loginModalTits = driver.findElements(By.className("modal-login-tit"));
            if(loginModalTits != null && loginModalTits.size() != 0
                    && !StringUtils.equals("none", loginModalTits.get(0).getCssValue("display"))) {
                printUtil("尚未登录~~~");
                throw new TicketProcessException("提单预览页展示未登录！");
            }

            List<WebElement> warnAlertElements = driver.findElements(By.id("content-defaultwarningAlert_id"));
            if(warnAlertElements != null && warnAlertElements.size() != 0) {
                System.out.println("已经购买了~~~");
                throw new TicketProcessException("恭喜，已经购买了！");
            }
        }
    }

    private void printUtil(Object msg) {
        System.out.print("当前线程索引：" + this.baseIndex + "；当前时间：" + DateUtil.convertStampToDate(System.currentTimeMillis()) + ";");
        System.out.println("日志内容=>" + msg);
    }
}
