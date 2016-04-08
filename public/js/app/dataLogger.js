/**
 *
 */
var app = angular.module('dataLoggerApp',
		[ 'ngRoute',
		  'ngResource',
		  'ui.bootstrap',
		  'newInstrumentView',
		  'adam4017View',
		  'tapiConfigView',
		  'mtConfigView',
		  'verewaConfigView',
		  'mtRealtimeChartView',
		  'miniMonitorTypeListView',
		  'monitorTypeListView']);

app.factory('BreadcrumbService', [ function() {
	var service = {
		title : ""
	};
	return service;
} ]);

app.config([ '$routeProvider', function($routeProvider) {
	$routeProvider.when('/', {
		templateUrl : '/Dashboard',
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "即時資訊>即時趨勢";
			} ]
		}
	}).when('/RealtimeStatus', {
		templateUrl : "/RealtimeStatus",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "即時資訊>即時數據";
			} ]
		}
	}).when('/History', {
		templateUrl : "/History",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "數據查詢>歷史資料查詢";
			} ]
		}
	}).when('/HistoryTrend', {
		templateUrl : "/HistoryTrend",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "數據查詢>歷史趨勢圖";
			} ]
		}
	}).when('/Calibration', {
		templateUrl : "/Calibration",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "數據查詢>校正資料查詢";
			} ]
		}
	}).when('/Alarm', {
		templateUrl : "/Alarm",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "數據查詢>警報記錄查詢";
			} ]
		}
	}).when('/Report', {
		templateUrl : "/Report",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "報表查詢>監測報表";
			} ]
		}
	}).when('/MonitorMonthlyHourReport', {
		templateUrl : "/MonitorMonthlyHourReport",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "報表查詢>月份時報表";
			} ]
		}
	}).when('/ManageInstrument', {
		templateUrl : "/ManageInstrument",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "系統管理>儀器管理";
			} ]
		}
	}).when('/InstrumentStatus', {
		templateUrl : "/InstrumentStatus",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "系統管理>儀器狀態查詢";
			} ]
		}
	}).when('/MonitorTypeConfig', {
		templateUrl : "/MonitorTypeConfig",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "系統管理>測項管理";
			} ]
		}
	}).when('/ManualAudit', {
		templateUrl : "/ManualAudit",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "系統管理>人工資料註記";
			} ]
		}
	}).when('/ManualAuditQuery', {
		templateUrl : "/ManualAuditQuery",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "系統管理>人工註記查詢";
			} ]
		}
	}).when('/UserManagement', {
		templateUrl : "/UserManagement",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "系統管理>使用者管理";
			} ]
		}
	}).when('/EventLog', {
		templateUrl : "/EventLog",
		resolve : {
			breadcrumb : [ 'BreadcrumbService', function(bs) {
				return bs.title = "系統管理>事件紀錄";
			} ]
		}
	}).otherwise({
		redirectTo : '/'
	});
} ]);

app.controller('MenuCtrl', [ 'BreadcrumbService', function($breadcrumb) {
	var self = this;
	self.breadcrumb = $breadcrumb;
} ]);

app.factory('MonitorTypeService', [ '$resource', function($resource) {
	return $resource('/MonitorType/:id');
} ]);
