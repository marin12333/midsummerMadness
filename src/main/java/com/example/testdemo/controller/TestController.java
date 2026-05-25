package com.example.testdemo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {

    @GetMapping("/hello")
    public String testDemo(@RequestParam("param") String param){
        return param;
    }

    @GetMapping("/sort")
    public String sortNumbers(
            @RequestParam("numbers") String numbers,
            @RequestParam("type") String type) {

        // 验证输入是否为纯数字字符串
        if (!numbers.matches("\\d+")) {
            return "错误：输入必须为纯数字字符串";
        }

        // 转换为整数数组
        int[] arr = new int[numbers.length()];
        for (int i = 0; i < numbers.length(); i++) {
            arr[i] = Character.getNumericValue(numbers.charAt(i));
        }

        // 根据类型选择排序算法
        if ("bubble".equalsIgnoreCase(type)) {
            bubbleSort(arr);
        } else if ("insertion".equalsIgnoreCase(type)) {
            insertionSort(arr);
        } else {
            return "错误：不支持的排序类型，请使用 'bubble' 或 'insertion'";
        }

        // 转换回字符串
        StringBuilder result = new StringBuilder();
        for (int num : arr) {
            result.append(num);
        }

        return result.toString();
    }

    // 冒泡排序
    private void bubbleSort(int[] arr) {
        int n = arr.length;
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - i - 1; j++) {
                if (arr[j] > arr[j + 1]) {
                    // 交换元素
                    int temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                }
            }
        }
    }

    // 插入排序
    private void insertionSort(int[] arr) {
        int n = arr.length;
        for (int i = 1; i < n; i++) {
            int key = arr[i];
            int j = i - 1;

            // 将大于key的元素向后移动
            while (j >= 0 && arr[j] > key) {
                arr[j + 1] = arr[j];
                j = j - 1;
            }
            arr[j + 1] = key;
        }
    }
}