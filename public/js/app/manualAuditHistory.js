/**
 * 
 */
angular.module('manualAuditHistoryView',
				[ 'ui.bootstrap.datetimepicker', 
				  'ui.dateTimeInput',
						'smart-table' ])
.controller('manualAuditHistoryCtrl',
[
	'MonitorTypeService',
	'$scope',
	'$http',
function($monitorType, $scope, $http) {
	var self = this;
	self.monitorTypeList = $monitorType.query();
	self.activeMt = function(mt) {
		return (mt.measuringBy || mt.measuredBy)
	}

	self.dateRangeStart = moment(23, "HH").subtract(2, 'days');
	self.dateRangeEnd = moment(23, "HH");

	self.beforeRenderStartDate = function($view,
			$dates, $leftDate, $upDate, $rightDate) {
		if (self.dateRangeEnd) {
			var activeDate = moment(self.dateRangeEnd);
			for (var i = 0; i < $dates.length; i++) {
				if ($dates[i].localDateValue() >= activeDate
						.valueOf())
					$dates[i].selectable = false;
			}
		}
	}

	self.beforeRenderEndDate = function($view, $dates,
			$leftDate, $upDate, $rightDate) {
		if ($scope.dateRangeStart) {
			var activeDate = moment(self.dateRangeStart)
					.subtract(1, $view)
					.add(1, 'minute');
			for (var i = 0; i < $dates.length; i++) {
				if ($dates[i].localDateValue() <= activeDate
						.valueOf()) {
					$dates[i].selectable = false;
				}
			}
		}
	}
	self.displayTime = function(t) {
		return moment.unix(t / 1000).format(
				"YYYY-MM-DD HH:mm");
	}
	
	$scope.displayCollection = [];
	$scope.logList = [];
	self.showRecord = false;
	self.noData = true;
	self.displayMt = function getSelectedMt(mt) {
		for (var i = 0; i < self.monitorTypeList.length; i++) {
			if (self.monitorTypeList[i]._id == mt)
				return self.monitorTypeList[i].desp;
		}
		return "";
	}
	self.query = function() {
		var url = "/ManualAuditHistory/" + self.dateRangeStart.valueOf()
				+ "/" + self.dateRangeEnd.valueOf();

		$http.get(url).then(
						function(response) {
							self.showRecord = true;
							$scope.logList=response.data;
							self.noData = $scope.logList.length == 0;
						}, function(error) {});
	}

	
	
			
}]);