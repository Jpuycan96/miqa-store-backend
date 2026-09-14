package com.miqa.store.admin;
public class AdminFailure extends RuntimeException {
 private final int status;
 public AdminFailure(int status,String message){super(message);this.status=status;}
 public int status(){return status;}
}
