package com.heytap.health.account;
public class AccountHelper {
 public static class Account { public String ssoid="fixture-account-A";public String getSsoid(){return ssoid;} }
 private static final Account ACCOUNT=new Account();public static Account getAccountManager(){return ACCOUNT;}
}
