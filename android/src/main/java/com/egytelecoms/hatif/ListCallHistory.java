package com.egytelecoms.hatif;

import java.util.List;

public class ListCallHistory {
   private List<CallHistory> callHistoryList;

   public List<CallHistory> getCallHistoryList() {
      return callHistoryList;
   }

   public void setCallHistoryList(List<CallHistory> callHistoryList) {
      this.callHistoryList = callHistoryList;
   }

   @Override
   public String toString() {
      return "ListCallHistory{" +
              "callHistoryList=" + callHistoryList +
              '}';
   }
}
